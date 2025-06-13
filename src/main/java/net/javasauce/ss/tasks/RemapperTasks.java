package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.maven.MavenNotation;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.Hashing;
import net.javasauce.ss.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class RemapperTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemapperTasks.class);

    private static final MavenNotation REMAPPER_TOOL = MavenNotation.parse("net.covers1624:FastRemapper:0.3.2.18:all");

    public static void runRemapper(SnowShovel ss, Path input, Path mappings, Path output) throws IOException {
        Path remapperJar = DownloadTasks.downloadFile(ss.http, REMAPPER_TOOL.toURL("https://maven.covers1624.net/").toString(), REMAPPER_TOOL.toPath(ss.toolsDir));
        LOGGER.info("Remapping {} with {}", input, REMAPPER_TOOL);

        Path hashOutput = output.resolveSibling(output.getFileName() + ".sha1");
        if (Files.exists(output) && Files.exists(hashOutput)) {
            if (Files.readString(hashOutput, StandardCharsets.UTF_8).trim().equals(computeHash(remapperJar, input, mappings, output))) {
                LOGGER.info(" Skipping, cache hit.");
                return;
            }
        }

        Path javaHome = ss.jdkProvider.findOrProvisionJdk(JavaVersion.JAVA_17);
        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome, true),
                List.of(
                        "-jar",
                        remapperJar.toAbsolutePath().toString(),
                        "--input",
                        input.toAbsolutePath().toString(),
                        "--mappings",
                        mappings.toAbsolutePath().toString(),
                        "--output",
                        output.toAbsolutePath().toString(),
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
        Files.writeString(hashOutput, computeHash(remapperJar, input, mappings, output), StandardCharsets.UTF_8);
    }

    private static String computeHash(Path toolJar, Path inputJar, Path mappings, Path output) throws IOException {
        var hasher = Hashing.digest(Hashing.SHA1);
        Hashing.addFileBytes(hasher, toolJar);
        Hashing.addFileBytes(hasher, inputJar);
        Hashing.addFileBytes(hasher, mappings);
        Hashing.addFileBytes(hasher, output);
        return Hashing.toString(hasher);
    }
}
