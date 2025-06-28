package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 6/25/25.
 */
public class PrepareToolTask extends Task {

    public final TaskInput<MavenNotation> notation = input("notation");
    public final TaskInput<Path> tool = input("tool");
    public final TaskInput<Path> toolDir = input("toolDir");

    public final TaskOutput<PreparedTool> output = computedOutput("output");

    private PrepareToolTask(String name, Executor executor) {
        super(name, executor);
    }

    public static PrepareToolTask create(String name, Executor executor, Consumer<PrepareToolTask> configure) {
        var task = new PrepareToolTask(name, executor);
        configure.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var notation = this.notation.get();
        var toolInput = this.tool.get();
        var extractedDir = toolDir.get().resolve(notation.toModulePath())
                .resolve(requireNonNull(notation.version))
                .resolve(notation.module + "-" + notation.version);

        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(toolInput))) {
            ZipEntry ent;
            while ((ent = zin.getNextEntry()) != null) {
                if (ent.isDirectory()) continue;

                Files.copy(zin, IOUtils.makeParents(extractedDir.resolve(ent.getName())), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path toolJar = extractedDir.resolve(notation.withExtension("jar").toFileName());

        output.set(new PreparedTool(
                toolJar,
                extractedDir
        ));
    }

    public record PreparedTool(
            Path toolJar,
            Path workingDir
    ) { }
}
