package org.jetbrains.intellij

import java.io.File
import java.util.function.BiFunction

interface Analyzer {

    // Returns true if analysis executed successfully
    fun analyze(
            files: Collection<File>,
            projectName: String,
            moduleName: String,
            ideaHomeDirectory: File,
            parameters: AnalyzerParameters
    ): Boolean

    fun setLogger(logger: BiFunction<Int, String, Unit>)

    fun shutdownIdea()
}