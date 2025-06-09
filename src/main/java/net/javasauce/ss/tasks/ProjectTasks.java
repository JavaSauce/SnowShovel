package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.io.IndentPrintWriter;
import net.javasauce.ss.tasks.LibraryTasks.LibraryDownload;
import net.javasauce.ss.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class ProjectTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectTasks.class);

    public static void generateProjectFiles(Path projectDir, JavaVersion javaVersion, List<LibraryDownload> libraries) throws IOException {
        LOGGER.info("Configuring project..");
        emitBuildGradle(projectDir, javaVersion, libraries);
        emitSettingsGradle(projectDir);
        emitGitIgnore(projectDir);
        emitReadme(projectDir);
        var procResult = ProcessUtils.runProcess(
                "gradle",
                List.of("wrapper", "--gradle-version", "8.10.2"),
                projectDir,
                LOGGER::info
        );
        procResult.assertExitCode(0);
    }

    private static void emitBuildGradle(Path projectDir, JavaVersion javaVersion, List<LibraryDownload> libraries) throws IOException {
        try (IndentPrintWriter pw = new IndentPrintWriter(Files.newOutputStream(IOUtils.makeParents(projectDir.resolve("build.gradle"))))) {
            pw.println("plugins {");
            pw.pushIndent();
            pw.println("id 'java'");
            pw.popIndent();
            pw.println("}");

            pw.println();

            pw.println("java {");
            pw.pushIndent();
            pw.println("toolchain {");
            pw.pushIndent();
            pw.println("languageVersion = JavaLanguageVersion.of(" + (javaVersion.ordinal() + 1) + ")");
            pw.popIndent();
            pw.println("}");
            pw.popIndent();
            pw.println("}");

            pw.println();

            pw.println("repositories {");
            pw.pushIndent();
            pw.println("mavenCentral()");
            pw.println("maven { url 'https://libraries.minecraft.net/' }");
            pw.popIndent();
            pw.println("}");

            pw.println();

            pw.println("dependencies {");
            pw.pushIndent();
            for (LibraryDownload library : libraries) {
                pw.println("implementation('" + library.notation() + "') { transitive = false }");
            }
            pw.popIndent();
            pw.println("}");
        }
    }

    private static void emitSettingsGradle(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                    }
                }
                
                plugins {
                    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
                }
                
                rootProject.name = 'Minecraft Client'
                """
        );
    }

    private static void emitGitIgnore(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(".gitignore"), """
                # exclude all
                /*
                
                # Include Important Folders
                !src/
                
                # Gradle stuff
                !gradle/
                !gradlew
                !gradlew.bat
                !build.gradle
                !settings.gradle
                
                # Include git important files
                !.gitignore
                
                # Other files.
                !README.md
                !decompile_report.txt
                """
        );
    }

    private static void emitReadme(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("README.md"), """
                # Shoveled
                Output of SnowShovel
                """
        );
    }
}
