package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.io.IndentPrintWriter;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.tasks.LibraryTasks.LibraryDownload;
import net.javasauce.ss.tasks.report.GenerateReportTask;
import net.javasauce.ss.tasks.report.TestCaseDef;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 1/21/25.
 */
public class ProjectTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectTasks.class);

    // Sick of waiting for `gradle wrapper --gradle-version blah`, just ran it and put it on a web server lol.
    private static final String GRADLE_WRAPPER_DIST = "https://covers1624.net/Files/GradleWrapper-8.10.2.zip";
    private static final long GRADLE_WRAPPER_DIST_LEN = 44825;
    private static final String GRADLE_WRAPPER_DIST_HASH = "2e355d2ede2307bfe40330db29f52b9b729fd9b2";

    public static void generateProjectFiles(SnowShovel ss, JavaVersion javaVersion, List<LibraryDownload> libraries, String mcVersion, @Nullable TestCaseDef testStats) throws IOException {
        LOGGER.info("Configuring project..");
        emitBuildGradle(ss.repoDir, javaVersion, libraries);
        emitSettingsGradle(ss.repoDir);
        emitGitIgnore(ss.repoDir);
        emitReadme(ss.repoDir, mcVersion, testStats);
        var zip = DownloadTasks.downloadFile(
                ss.http,
                GRADLE_WRAPPER_DIST,
                ss.librariesDir.resolve("GradleWrapper.zip"),
                GRADLE_WRAPPER_DIST_LEN,
                GRADLE_WRAPPER_DIST_HASH
        );
        ZipTasks.extractZip(zip, ss.repoDir);
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

    private static void emitReadme(Path projectDir, String mcVersion, @Nullable TestCaseDef testStats) throws IOException {
        var readme = """
                # Shoveled
                Output of SnowShovel
                """;
        if (testStats != null) {
            readme += GenerateReportTask.generateReport(List.of(new GenerateReportTask.ReportPair(mcVersion, testStats)));
        }

        Files.writeString(projectDir.resolve("README.md"), readme);
    }
}
