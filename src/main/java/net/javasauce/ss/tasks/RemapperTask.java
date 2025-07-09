package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.ProcessUtils;
import net.javasauce.ss.util.TaskCache;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 1/21/25.
 */
public class RemapperTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemapperTask.class);

    public final TaskInput<PrepareToolTask.PreparedTool> tool = input("tool");
    public final TaskInput<Path> javaHome = input("javaHome");
    public final TaskInput<Path> input = input("input");
    public final TaskInput<Path> mappings = input("mappings");
    public final TaskOutput<Path> remapped = output("remapped");

    private RemapperTask(String name, Executor executor) {
        super(name, executor);

        withCaching(remapped, cache -> {
            cache.add(tool, PrepareToolTask.PreparedTool::toolJar);
            cache.add(input);
            cache.add(mappings);
            cache.add(remapped);
        });
    }

    public static RemapperTask create(String name, Executor executor, Consumer<RemapperTask> configure) {
        var task = new RemapperTask(name, executor);
        configure.accept(task);
        return task;
    }

    @Override
    protected void execute() {
        var tool = this.tool.get();
        Path input = this.input.get();
        Path mappings = this.mappings.get();
        Path remapped = this.remapped.get();
        LOGGER.info("Remapping {} with {}", this.input, tool.toolJar());

        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome.get(), true),
                List.of(
                        "-jar",
                        tool.toolJar().toAbsolutePath().toString(),
                        "--input",
                        input.toAbsolutePath().toString(),
                        "--mappings",
                        mappings.toAbsolutePath().toString(),
                        "--output",
                        remapped.toAbsolutePath().toString(),
                        "--flip",
                        "--fix-locals",
                        "--fix-source",
                        "--fix-ctor-anns",
                        "--fix-stripped-ctors",
                        "--fix-record-ctor-param-names"
                ),
                tool.workingDir(),
                LOGGER::info
        );
        procResult.assertExitCode(0);
    }

    @Deprecated
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
                        "--fix-stripped-ctors"
                ),
                remapperJar.getParent(),
                LOGGER::info
        );
        procResult.assertExitCode(0);
        cache.writeCache();
    }
}
