package net.javasauce.ss.tasks;

import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.util.UnzipTask;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 6/25/25.
 */
public class PrepareToolTask extends Task {

    public final TaskInput<MavenNotation> notation = input("notation");
    public final TaskInput<Path> toolDir = input("toolDir");

    public final TaskOutput<PreparedTool> output = computedOutput("output");

    private final TaskInput<Path> extractedDir = input("extractedDir");

    private PrepareToolTask(String name, Executor executor, HttpEngine http) {
        super(name, executor);

        var downloadToolTask = DownloadTask.create(name + "_download", executor, http, task -> {
            task.output.deriveFrom(toolDir, notation, (t, n) -> n.toPath(t));
            task.url.deriveFrom(notation, e -> e.toURL("https://maven.covers1624.net").toString());
            task.localOverride.deriveFrom(notation, e -> Optional.ofNullable(findMavenLocalFile(e)));
        });
        declareCompositeTask(downloadToolTask);

        var extractTask = UnzipTask.create(name + "_unzip", executor, task -> {
            task.zip.set(downloadToolTask.output);
            task.output.deriveFrom(toolDir, notation, (t, n) ->
                    t.resolve(n.toModulePath())
                            .resolve(requireNonNull(n.version))
                            .resolve(n.module + "-" + n.version));
        });
        declareCompositeTask(extractTask);

        extractedDir.set(extractTask.output);
    }

    public static PrepareToolTask create(String name, Executor executor, HttpEngine http, Consumer<PrepareToolTask> configure) {
        var task = new PrepareToolTask(name, executor, http);
        configure.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var notation = this.notation.get();
        var extractedDir = this.extractedDir.get();

        Path toolJar = extractedDir.resolve(notation.withExtension("jar").toFileName());

        output.set(new PreparedTool(
                toolJar,
                extractedDir
        ));
    }

    private static @Nullable Path findMavenLocalFile(MavenNotation notation) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) return null;

        Path mavenLocalFile = notation.toPath(Path.of(userHome).resolve(".m2/repository"));
        if (!Files.exists(mavenLocalFile)) return null;

        return mavenLocalFile;
    }

    public record PreparedTool(
            Path toolJar,
            Path workingDir
    ) { }
}
