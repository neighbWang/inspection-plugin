package org.jetbrains.idea.inspections.runners

import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.idea.createCommandLineApplication
import com.intellij.idea.getCommandLineApplication
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PlatformUtils
import org.jetbrains.idea.inspections.control.DisableSystemExit
import org.jetbrains.intellij.ProxyLogger
import org.jetbrains.intellij.parameters.IdeaRunnerParameters
import org.jetbrains.intellij.plugins.Plugin
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption


abstract class IdeaRunner<T>(logger: ProxyLogger) : Runner<IdeaRunnerParameters<T>>(logger) {

    abstract fun analyze(project: Project, parameters: T): Boolean

    private fun openProject(projectDir: File, projectName: String, moduleName: String): Project {
        logger.info("Before project creation at '$projectDir'")
        var ideaProject: Project? = null
        val projectFile = File(projectDir, projectName + ProjectFileType.DOT_DEFAULT_EXTENSION)
        invokeAndWait {
            ideaProject = ProjectUtil.openOrImport(
                    projectFile.absolutePath,
                    /* projectToClose = */ null,
                    /* forceOpenInNewFrame = */ true
            )
        }
        return ideaProject?.apply {
            val rootManager = ProjectRootManager.getInstance(this)
            logger.info("Project SDK name: " + rootManager.projectSdkName)
            logger.info("Project SDK: " + rootManager.projectSdk)

            val modules = ModuleManager.getInstance(this).modules.toList()
            for (module in modules) {
                if (module.name != moduleName) continue
                val moduleRootManager = ModuleRootManager.getInstance(module)
                val dependencyEnumerator =
                        moduleRootManager.orderEntries().compileOnly().recursively().exportedOnly()
                var dependsOnKotlinCommon = false
                var dependsOnKotlinJS = false
                var dependsOnKotlinJVM = false
                dependencyEnumerator.forEach { orderEntry ->
                    if (orderEntry is LibraryOrderEntry) {
                        val library = orderEntry.library
                        if (library != null) {
                            if (library.getUrls(OrderRootType.CLASSES).any { "$KT_LIB-common" in it }) {
                                dependsOnKotlinCommon = true
                            }
                            if (library.getUrls(OrderRootType.CLASSES).any { "$KT_LIB-js" in it }) {
                                dependsOnKotlinJS = true
                            }
                            if (library.getUrls(OrderRootType.CLASSES).any { "$KT_LIB-jdk" in it || "$KT_LIB-1" in it }) {
                                dependsOnKotlinJVM = true
                            }
                        }
                    }
                    true
                }
                when {
                    dependsOnKotlinJVM ->
                        logger.info("Under analysis: Kotlin JVM module $module with SDK: " + moduleRootManager.sdk)
                    dependsOnKotlinJS ->
                        logger.warn("Under analysis: Kotlin JS module $module (JS SDK is not supported yet)")
                    dependsOnKotlinCommon ->
                        logger.warn("Under analysis: Kotlin common module $module (common SDK is not supported yet)")
                    else ->
                        logger.info("Under analysis: pure Java module $module with SDK: " + moduleRootManager.sdk)
                }
            }
        } ?: run {
            throw RunnerException("Cannot open IDEA project: '${projectFile.absolutePath}'")
        }
    }

    fun invokeAndWait(action: () -> Unit) = idea.invokeAndWait(action)

    fun runReadAction(action: () -> Unit) = idea.runReadAction(action)

    private var application: ApplicationEx? = null

    enum class LockStatus {
        FREE, USED, SKIP
    }

    private var systemLockChannel: FileChannel? = null
    private var systemLock: FileLock? = null

    private val idea: ApplicationEx
        get() = application ?: throw IllegalStateException("Idea not runned")

    private data class BuildConfiguration(val buildNumber: String, val usesUltimate: Boolean)

    private val File.buildConfiguration: BuildConfiguration
        get() = let { buildFile ->
            if (buildFile.exists()) {
                val text = buildFile.readText()
                val usesUltimate = text.startsWith("IU")
                text.dropWhile { !it.isDigit() }.let {
                    BuildConfiguration(if (it.isNotEmpty()) it else DEFAULT_BUILD_NUMBER, usesUltimate)
                }
            } else {
                BuildConfiguration(DEFAULT_BUILD_NUMBER, false)
            }
        }

    override fun run(parameters: IdeaRunnerParameters<T>): Boolean {
        logger.info("Class loader: " + this.javaClass.classLoader)
        try {
            with(parameters) {
                application = loadApplication(ideaVersion, ideaHomeDirectory, ideaSystemDirectory, plugins)
                @Suppress("DEPRECATION")
                application?.doNotSave()
                application?.configureJdk()
                val project = openProject(projectDir, projectName, moduleName)
                return analyze(project, parameters.childParameters)
            }
        } catch (e: Throwable) {
            if (e is RunnerException) throw e
            throw RunnerException("Exception caught in inspection plugin: $e", e)
        } finally {
            releaseSystemLock()
        }
    }

    private fun Application.configureJdk() {
        logger.info("Before SDK configuration")
        invokeAndWait {
            runWriteAction {
                val javaHomePath = System.getenv(JAVA_HOME) ?: ""
                val jdkTable = ProjectJdkTable.getInstance()
                for ((jdkVersion, jdkEnvironmentVariable) in JDK_ENVIRONMENT_VARIABLES) {
                    if (jdkTable.findJdk(jdkVersion) != null) continue
                    val homePath = System.getenv(jdkEnvironmentVariable)
                            ?: if (jdkVersion in javaHomePath && "jdk" in javaHomePath) javaHomePath else continue
                    logger.info("Configuring JDK $jdkVersion")
                    val sdk = SdkConfigurationUtil.createAndAddSDK(
                            FileUtil.toSystemIndependentName(homePath),
                            JavaSdk.getInstance()
                    ) ?: continue
                    logger.info("Home path is ${sdk.homePath}, version string is ${sdk.versionString}")
                }
            }
        }
    }

    private fun checkCompatibility(plugin: Plugin, plugins: List<IdeaPluginDescriptor>, buildNumber: String, ideaVersion: String) {
        val descriptor = plugins.find { it.name == plugin.name } ?: throw RunnerException("${plugin.name} not loaded")
        if (PluginManagerCore.isIncompatible(descriptor)) throw RunnerException("${plugin.name} not loaded")
        val pluginDescriptor = Plugin.PluginDescriptor(descriptor.version, descriptor.sinceBuild, descriptor.untilBuild)
        val ideaDescriptor = Plugin.IdeaDescriptor(ideaVersion, buildNumber)
        val reason = plugin.checkCompatibility(pluginDescriptor, ideaDescriptor) ?: return
        logger.info(reason)
        throw RunnerException("${plugin.name} not loaded")
    }

    private fun loadApplication(ideaVersion: String, ideaHomeDirectory: File, ideaSystemDirectory: File, plugins: List<Plugin>): ApplicationEx {
        val ideaBuildNumberFile = File(ideaHomeDirectory, "build.txt")
        val (buildNumber, usesUltimate) = ideaBuildNumberFile.buildConfiguration
        if (usesUltimate) throw RunnerException("Using of IDEA Ultimate is not yet supported in inspection runner")
        val systemPath = generateSystemPath(ideaSystemDirectory, buildNumber, usesUltimate)
        val pluginsPath = plugins.joinToString(":") { it.directory.absolutePath }
        val platformPrefix = if (usesUltimate) PlatformUtils.IDEA_PREFIX else PlatformUtils.IDEA_CE_PREFIX

        System.setProperty(IDEA_HOME_PATH, ideaHomeDirectory.path)
        System.setProperty(AWT_HEADLESS, "true")
        System.setProperty(BUILD_NUMBER, buildNumber)
        System.setProperty(SYSTEM_PATH, systemPath)
        System.setProperty(PLUGINS_PATH, pluginsPath)
        System.setProperty(PlatformUtils.PLATFORM_PREFIX_KEY, PlatformUtils.getPlatformPrefix(platformPrefix))

        logger.info("IDEA home path: $ideaHomeDirectory")
        logger.info("IDEA home path: ${PathManager.getHomePath()}")
        logger.info("IDEA plugin path: $pluginsPath")
        logger.info("IDEA plugin path: ${PathManager.getPluginsPath()}")
        logger.info("IDEA system path: $systemPath")
        if (getCommandLineApplication() != null) {
            logger.info("IDEA command line application already exists, don't bother to run it again.")
            val realIdeaHomeDirectory = File(PathManager.getHomePath())
            val ideaHomePath = ideaHomeDirectory.canonicalPath
            val realIdeaHomePath = realIdeaHomeDirectory.canonicalPath
            if (ideaHomePath != realIdeaHomePath)
                throw RunnerException("IDEA command line application already exists, but have other instance: $ideaHomePath and $realIdeaHomePath")
            return ApplicationManagerEx.getApplicationEx()
        }
        logger.info("IDEA starting in command line mode")
        createCommandLineApplication(isInternal = false, isUnitTestMode = false, isHeadless = true)
        USELESS_PLUGINS.forEach { PluginManagerCore.disablePlugin(it) }

        // Do not remove the call of PluginManagerCore.getPlugins(), it prevents NPE in IDEA
        // NB: IdeaApplication.getStarter() from IJ community contains the same call
        val enabledPlugins = PluginManagerCore.getPlugins().toList()
        val disabledPlugins = PluginManagerCore.getDisabledPlugins()
        logger.info("Enabled plugins: ${enabledPlugins.map { it.name to it.pluginId }.toMap()}")
        logger.info("Disabled plugins ${disabledPlugins.toList()}")
        plugins.forEach { checkCompatibility(it, enabledPlugins, buildNumber, ideaVersion) }
        return ApplicationManagerEx.getApplicationEx().apply { load() }
    }

    private fun generateSystemPath(ideaSystemDirectory: File, buildNumber: String, usesUltimate: Boolean): String {
        val buildPrefix = (if (usesUltimate) "U_" else "") + buildNumber.replace(".", "_")
        var file: File
        var code = 0
        do {
            if (code++ == 256) throw RunnerException("Cannot create IDEA system directory (all locked)")
            file = File(ideaSystemDirectory, "${buildPrefix}_code$code/system")
            if (!file.exists()) file.mkdirs()
            val systemMarkerFile = File(file, SYSTEM_MARKER_FILE)
            // To prevent usages by multiple processes
            val status = acquireSystemLockIfNeeded(systemMarkerFile)
            if (status == LockStatus.SKIP) {
                throw RunnerException("IDEA system path is already used in current process")
            }
        } while (status == LockStatus.USED)
        return file.absolutePath
    }

    private fun acquireLockIfNeeded(lockType: String, lockFile: File): Triple<LockStatus, FileLock?, FileChannel?> {
        val lockDirectory: File? = lockFile.parentFile
        if (lockDirectory != null && !lockDirectory.exists()) lockDirectory.mkdirs()
        val lockName = lockType + " lock " + lockFile.name
        if (!lockFile.exists()) lockFile.createNewFile()
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE)
        val (status, lock) = try {
            val lock = channel?.tryLock()
            val status = if (lock == null) LockStatus.USED else LockStatus.FREE
            Pair(status, lock)
        } catch (ignore: OverlappingFileLockException) {
            Pair(LockStatus.SKIP, null)
        } catch (e: IOException) {
            logger.warn("IO exception while locking: ${e.message}")
            throw RunnerException("Exception caught in inspection plugin (IDEA $lockName locking): $e", e)
        }
        when (status) {
            LockStatus.SKIP -> logger.info("$lockName used by current process.")
            LockStatus.USED -> logger.info("$lockName used by another process.")
            LockStatus.FREE -> logger.info("$lockName acquired")
        }
        return Triple(status, lock, channel)
    }

    private fun lockRelease(name: String, lock: FileLock?, channel: FileChannel?) {
        lock?.release()
        channel?.close()
        logger.info("$name lock released")
    }

    private fun acquireSystemLockIfNeeded(systemLockFile: File): LockStatus {
        val (status, lock, channel) = acquireLockIfNeeded("System", systemLockFile)
        systemLock = lock
        systemLockChannel = channel
        return status
    }

    private fun releaseSystemLock() {
        lockRelease("System", systemLock, systemLockChannel)
        systemLock = null
        systemLockChannel = null
    }

    override fun finalize() {
        logger.info("IDEA shutting down.")
        val application = application
        DisableSystemExit().use {
            when (application) {
                is ApplicationImpl -> application.exit(true, true, false)
                else -> application?.exit(true, true)
            }
            // Wait IDEA shutdown
            application?.invokeAndWait { }
        }
    }

    companion object {
        private const val AWT_HEADLESS = "java.awt.headless"
        private const val IDEA_HOME_PATH = "idea.home.path"
        private const val BUILD_NUMBER = "idea.plugins.compatible.build"
        private const val SYSTEM_PATH = "idea.system.path"
        private const val PLUGINS_PATH = "plugin.path"
        private const val SYSTEM_MARKER_FILE = "marker.ipl"
        private const val JAVA_HOME = "JAVA_HOME"
        private val JDK_ENVIRONMENT_VARIABLES = mapOf(
                "1.6" to "JDK_16",
                "1.7" to "JDK_17",
                "1.8" to "JDK_18"
                // TODO: un-comment me
                //"9" to "JDK_9"
        )

        // TODO: change to USEFUL_PLUGINS
        private val USELESS_PLUGINS = listOf(
                "mobi.hsz.idea.gitignore",
                "org.jetbrains.plugins.github",
                "Git4Idea",
                "org.jetbrains.android"
        )

        private const val KT_LIB = "kotlin-stdlib"

        private const val DEFAULT_BUILD_NUMBER = "172.1"
    }
}