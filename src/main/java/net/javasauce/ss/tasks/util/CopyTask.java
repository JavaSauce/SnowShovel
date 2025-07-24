package net.javasauce.ss.tasks.util;

import net.covers1624.quack.io.CopyingFileVisitor;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Copies the specified file or directory content.
 * <p>
 * Created by covers1624 on 6/29/25.
 */
public class CopyTask extends Task {

    public final TaskInput<Path> input = input("input");
    public final TaskOutput<Path> output = output("output");

    private CopyTask(String name, Executor executor) {
        super(name, executor);
    }

    public static CopyTask create(String name, Executor executor, Consumer<CopyTask> cons) {
        CopyTask task = new CopyTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var input = this.input.get();
        var output = this.output.get();

        if (Files.isDirectory(input)) {
            if (Files.exists(output) && !Files.isDirectory(output)) {
                throw new IOException("Expected output to be directory for directory copy.");
            }
            Files.walkFileTree(input, new CopyingFileVisitor(input, output));
        } else {
            var fileOutput = output;
            if (Files.exists(output) && Files.isDirectory(output)) {
                fileOutput = output.resolve(input.getFileName());
            }
            Files.copy(input, fileOutput, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
