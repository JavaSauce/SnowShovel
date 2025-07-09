package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.ProcessUtils;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 1/21/25.
 */
public class DecompileTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecompileTask.class);

    public final TaskInput<Path> javaHome = input("javaHome");
    public final TaskInput<PrepareToolTask.PreparedTool> tool = input("tool");
    public final TaskInput.Collection<Path> libraries = inputCollection("libraries");
    public final TaskInput<Path> inputJar = input("inputJar");

    public final TaskOutput<Path> output = output("output");

    private DecompileTask(String name, Executor executor) {
        super(name, executor);
    }

    public static DecompileTask create(String name, Executor executor, Consumer<DecompileTask> cons) {
        DecompileTask task = new DecompileTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var output = this.output.get();

        List<Path> libraryPath = new ArrayList<>();
        libraryPath.add(inputJar.get());
        libraryPath.addAll(libraries.get());

        var tool = this.tool.get();
        var procResult = ProcessUtils.runProcess(
                JavaInstall.getJavaExecutable(javaHome.get(), true),
                List.of(
                        "-ea", "-XX:-OmitStackTraceInFastThrow",
                        "-Dcoffeegrinder.testcases.library=true",
                        "-Dcoffeegrinder.testcases.library.update_defs=true",
                        "-Dcoffeegrinder.test.update=true",
                        "-Dcoffeegrinder.test.output=" + output.resolve("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.compile_error_output=" + output.resolveSibling("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.rt_diff_output=" + output.resolve("src/main/java").toAbsolutePath(),
                        "-Dcoffeegrinder.test.stats=" + output.resolve("src/main/resources/test_stats.json").toAbsolutePath(),
                        "-Dcoffeegrinder.test.classes=" + inputJar.get().toAbsolutePath(),
                        "-Dcoffeegrinder.test.libraries=" + FastStream.of(libraryPath)
                                .map(Path::toAbsolutePath)
                                .map(Path::toString)
                                .join(File.pathSeparator),
                        "-jar", tool.toolJar().toAbsolutePath().toString(),
                        "execute",
                        "--scan-classpath",
                        "--include-engine", "testcase-library-engine",
                        "--details=summary"
                ),
                tool.workingDir(),
                LOGGER::info
        );
    }

    public static void decompileAndTest(SnowShovel ss, JavaVersion javaVersion, List<Path> libraries, Path inputJar, Path versionDir) {
        Path toolJar = ss.decompiler.getTool();

        Path javaHome = ss.jdkProvider.findOrProvisionJdk(javaVersion);
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
