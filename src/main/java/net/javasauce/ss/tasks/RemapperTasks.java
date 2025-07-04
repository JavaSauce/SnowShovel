package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.ProcessUtils;
import net.javasauce.ss.util.TaskCache;
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

    public static void runRemapper(SnowShovel ss, Path input, Path mappings, Path output) throws IOException {
        Path remapperJar = ss.fastRemapper.getTool();
        LOGGER.info("Remapping {} with {}", input, ss.fastRemapper.notation());

        TaskCache cache = TaskCache.forOutput(output)
                .add(remapperJar)
                .add(input)
                .add(mappings)
                .add(output);

        if (cache.isUpToDate()) {
            LOGGER.info(" Skipping, cache hit.");
            return;
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
                        "--fix-stripped-ctors",
                        "--fix-record-ctor-param-names"
                ),
                remapperJar.getParent(),
                LOGGER::info
        );
        procResult.assertExitCode(0);
        cache.writeCache();
    }
}
