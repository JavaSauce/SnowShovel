package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.util.JdkProvider;
import net.javasauce.ss.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class RemapperTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemapperTasks.class);

    private static final MavenNotation REMAPPER_TOOL = MavenNotation.parse("net.covers1624:FastRemapper:0.3.2.16:all");

    public static void runRemapper(HttpEngine http, JdkProvider jdkProvider, Path toolsDir, Path input, Path output, Path mappings) throws IOException {
        Path remapperJar = DownloadTasks.downloadFile(http, REMAPPER_TOOL.toURL("https://maven.covers1624.net/").toString(), REMAPPER_TOOL.toPath(toolsDir));
        Path javaHome = jdkProvider.findOrProvisionJdk(JavaVersion.JAVA_17);

        LOGGER.info("Remapping {} with {}", input, REMAPPER_TOOL);
        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome, true),
                List.of(
                        "-jar",
                        remapperJar.toAbsolutePath().toString(),
                        "--input",
                        input.toAbsolutePath().toString(),
                        "--output",
                        output.toAbsolutePath().toString(),
                        "--mappings",
                        mappings.toAbsolutePath().toString(),
                        "--flip",
                        "--fix-locals",
                        "--fix-source",
                        "--fix-ctor-anns",
                        "--fix-stripped-ctors"
                ),
                remapperJar.getParent(),
                LOGGER::info
        );
        procResult.assertExitCode(0);
    }
}
