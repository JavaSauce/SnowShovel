package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.util.JdkProvider;
import net.javasauce.ss.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class DecompileTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecompileTasks.class);

    private static final MavenNotation DECOMPILER_TEST_TOOL = MavenNotation.parse("net.javasauce:Decompiler:0:testframework@zip");

    public static void decompileAndTest(HttpEngine http, JdkProvider jdkProvider, Path toolsDir, String decompilerVersion, JavaVersion javaVersion, List<Path> libraries, Path inputJar, Path versionDir) {
        MavenNotation artifact = DECOMPILER_TEST_TOOL.withVersion(decompilerVersion);

        Path distZip = DownloadTasks.downloadFileWithMavenLocalFallback(http, "https://maven.covers1624.net", artifact, toolsDir);
        Path toolJar = ToolTasks.extractTool(toolsDir, artifact, distZip, !artifact.version.contains("SNAPSHOT"));

        Path javaHome = jdkProvider.findOrProvisionJdk(javaVersion);
        List<Path> libraryPath = new ArrayList<>();
        libraryPath.add(inputJar);
        libraryPath.addAll(libraries);

        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome, true),
                List.of(
                        "-ea", "-XX:-OmitStackTraceInFastThrow",
                        "-Dcoffeegrinder.testcases.library=true",
                        "-Dcoffeegrinder.testcases.library.update_defs=true",
                        "-Dcoffeegrinder.test.update=true",
                        "-Dcoffeegrinder.test.output=" + versionDir.resolve("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.compile_error_output=" + versionDir.resolve("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.rt_diff_output=" + versionDir.resolve("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.defs=" + versionDir.resolve("test_defs.json").toAbsolutePath(),
                        "-Dcoffeegrinder.test.stats=" + versionDir.resolve("src/main/resources/test_stats.json").toAbsolutePath(),
                        "-Dcoffeegrinder.test.classes=" + inputJar.toAbsolutePath(),
                        "-Dcoffeegrinder.test.libraries=" + FastStream.of(libraryPath)
                                .map(Path::toAbsolutePath)
                                .map(Path::toString)
                                .join(File.pathSeparator),
                        "-jar", toolJar.toAbsolutePath().toString(),
                        "execute",
                        "--scan-classpath",
                        "--include-engine", "testcase-library-engine",
                        "--details=summary"
                ),
                toolJar.getParent(),
                LOGGER::info
        );
    }
}
