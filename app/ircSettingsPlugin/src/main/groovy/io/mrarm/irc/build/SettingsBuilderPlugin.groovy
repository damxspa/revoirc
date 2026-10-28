package io.mrarm.irc.build

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

public class SettingsBuilderPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def android = (AppExtension) project.extensions.getByName("android")

        def genDir = project.layout.buildDirectory.dir("generated/source/settings")
        def genTask = project.tasks.register("generateSettings", GenerateSettingsTask) {
            it.sourceFile.set(project.layout.projectDirectory.file("settings.yml"))
            it.outputDir.set(genDir)
        }

        android.applicationVariants.all { v ->
            v.registerJavaGeneratingTask(genTask.get(), genDir.get().getAsFile())
        }
    }

}