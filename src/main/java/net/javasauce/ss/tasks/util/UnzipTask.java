package net.javasauce.ss.tasks.util;

import net.covers1624.quack.io.IOUtils;
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

/**
 * Created by covers1624 on 6/29/25.
 */
public class UnzipTask extends Task {

    public final TaskInput<Path> zip = input("zip");
    public final TaskOutput<Path> output = output("output");

    private UnzipTask(String name, Executor executor) {
        super(name, executor);
    }

    public static UnzipTask create(String name, Executor executor, Consumer<UnzipTask> cons) {
        var task = new UnzipTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var output = this.output.get();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.get()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                Files.copy(zin, IOUtils.makeParents(output.resolve(entry.getName())), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
