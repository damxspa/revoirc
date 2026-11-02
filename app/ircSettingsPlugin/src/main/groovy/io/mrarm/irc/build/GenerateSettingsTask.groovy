package io.mrarm.irc.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateSettingsTask extends DefaultTask {

    @InputFile
    abstract RegularFileProperty getSourceFile()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @TaskAction
    void generate() {
        SettingsBuilder.generateJavaFiles(getSourceFile().get().getAsFile(), getOutputDir().get().getAsFile())
    }
}
