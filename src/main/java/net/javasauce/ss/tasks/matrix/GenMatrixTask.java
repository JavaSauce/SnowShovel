package net.javasauce.ss.tasks.matrix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.javasauce.ss.util.RunRequest;
import net.javasauce.ss.util.matrix.JobMatrix;
import net.javasauce.ss.util.matrix.MatrixJob;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/21/25.
 */
public class GenMatrixTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenMatrixTask.class);

    private static final Gson GSON = new GsonBuilder().create();

    public final TaskInput<RunRequest> runRequest = input("runRequest");
    public final TaskInput<Integer> matrixSize = input("matrixSize");

    public final TaskOutput<Path> output = output("output");

    private GenMatrixTask(String name, Executor executor) {
        super(name, executor);
    }

    public static GenMatrixTask create(String name, Executor executor, Consumer<GenMatrixTask> cons) {
        var task = new GenMatrixTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var runRequest = this.runRequest.get();

        var jobs = FastStream.of(runRequest.versions())
                .partition(matrixSize.get())
                .map(e -> new RunRequest(
                        runRequest.reason(),
                        runRequest.decompilerVersion(),
                        e.toList()
                ))
                .map(e -> new MatrixJob("job", e))
                .toList();
        JobMatrix matrix = new JobMatrix(jobs);

        LOGGER.info("Writing job matrix..");
        JsonUtils.write(GSON, output.get(), matrix, StandardCharsets.UTF_8);
    }
}
