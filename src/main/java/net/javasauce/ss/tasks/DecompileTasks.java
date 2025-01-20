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
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class DecompileTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecompileTasks.class);

    private static final MavenNotation DECOMPILER_TOOL = MavenNotation.parse("net.javasauce:Decompiler:0@zip");

    public static void decompileJar(HttpEngine http, JdkProvider jdkProvider, Path toolsDir, String decompilerVersion, JavaVersion javaVersion, List<Path> libraries, Path inputJar, Path sourceOutput, Path astOutput) {
        MavenNotation artifact = DECOMPILER_TOOL.withVersion(decompilerVersion);
        Path distZip = DownloadTasks.downloadFile(http, artifact.toURL("https://maven.covers1624.net").toString(), artifact.toPath(toolsDir));
        Path decompilerJar = ToolTasks.extractTool(toolsDir, artifact, distZip);

        Path javaHome = jdkProvider.findOrProvisionJdk(javaVersion);

        // TODO We should write the log file somewhere
        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome, true),
                List.of(
                        "-ea", "-XX:-OmitStackTraceInFastThrow",
                        "-jar", decompilerJar.toAbsolutePath().toString(),
                        "--output", sourceOutput.toAbsolutePath().toString(),
                        "--output-ast", astOutput.toAbsolutePath().toString(),
                        "--input", inputJar.toAbsolutePath().toString(),
                        "--lib", FastStream.of(libraries)
                                .map(Path::toAbsolutePath)
                                .map(Path::toString)
                                .join(File.pathSeparator)
                ),
                decompilerJar.getParent(),
                LOGGER::info
        );
    }
}
