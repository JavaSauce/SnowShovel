package net.javasauce.ss.util.task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Represents some task input or output.
 * <p>
 * You may retrieve a {@link CompletableFuture} for the IO to operate on its output.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public abstract sealed class TaskIO<T> implements Supplier<T> permits TaskInput, TaskOutput {

    protected final Task task;
    private final String name;

    protected TaskIO(Task task, String name) {
        this.task = task;
        this.name = name;
    }

    /**
     * The future for access to this IO.
     *
     * @return The future.
     */
    public abstract CompletableFuture<T> getFuture();

    public final Task getTask() {
        return task;
    }

    public final String getName() {
        return name;
    }

    abstract boolean isValueSet();
}
