package net.javasauce.ss.tasks.util;

import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.io.IndentPrintWriter;
import net.javasauce.ss.tasks.LibraryTasks;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.task.TaskInput;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/29/25.
 */
public class GenerateGradleProjectTask extends GenProjectTask {

    public final TaskInput<Path> projectDir = input("projectDir");
    public final TaskInput<Path> gradleWrapperDist = input("gradleWrapperDist");
    public final TaskInput<JavaVersion> javaVersion = input("javaVersion");
    public final TaskInput<List<LibraryTasks.LibraryDownload>> libraries = input("libraries");
    public final TaskInput<String> mcVersion = input("mcVersion");

    private GenerateGradleProjectTask(String name, Executor executor) {
        super(name, executor);

        var zipTask = UnzipTask.create(name + "_unzipGradleDist", executor, task -> {
            task.zip.set(gradleWrapperDist);
            task.output.deriveFrom(projectDir, e -> e);
        });

        declareCompositeTask(zipTask);
    }

    public static GenerateGradleProjectTask create(String name, Executor executor, Consumer<GenerateGradleProjectTask> cons) {
        var task = new GenerateGradleProjectTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var projectDir = this.projectDir.get();

        Files.writeString(projectDir.resolve("build.gradle"), buildGradleScript(javaVersion.get(), libraries.get()));
        Files.writeString(projectDir.resolve("settings.gradle"), buildSettingsScript());
        Files.writeString(projectDir.resolve(".gitignore"), buildGitIgnore());

        TestCaseDef testStats = null;
        var testStatsFile = projectDir.resolve("src/main/resources/test_stats.json");
        if (Files.exists(testStatsFile)) {
            testStats = TestCaseDef.loadTestStats(testStatsFile);
        }
        Files.writeString(projectDir.resolve("README.md"), buildReadme(mcVersion.get(), testStats));
    }

    private String buildGradleScript(JavaVersion javaVersion, List<LibraryTasks.LibraryDownload> libraries) {
        var sw = new StringWriter();
        try (IndentPrintWriter pw = new IndentPrintWriter(new PrintWriter(sw, true))) {
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
            for (LibraryTasks.LibraryDownload library : libraries) {
                pw.println("implementation('" + library.notation() + "') { transitive = false }");
            }
            pw.popIndent();
            pw.println("}");
        }
        return sw.toString();
    }

    private String buildSettingsScript() {
        return """
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
                """;
    }

    private String buildGitIgnore() {
        return """
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
                """;
    }

    private String buildReadme(String mcVersion, @Nullable TestCaseDef testStats) {
        var readme = """
                # Shoveled
                Output of SnowShovel
                """;
        if (testStats != null) {
            readme += generateReport(Map.of(mcVersion, testStats));
        }
        return readme;
    }
}
