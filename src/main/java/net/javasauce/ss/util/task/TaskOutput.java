package net.javasauce.ss.util.task;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * An output for a task.
 * <p>
 * Task outputs may be declared as computed, indicating that their output is set
 * as a result of the task being executed.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public final class TaskOutput<T> extends TaskIO<T> {

    private final boolean isComputed;

    private @Nullable CompletableFuture<T> future;
    private @Nullable T value;

    TaskOutput(Task task, String name, boolean isComputed) {
        super(task, name);
        this.isComputed = isComputed;
    }

    /**
     * Get the future for this tasks output.
     * <p>
     * This future will only complete once this outputs task has completed.
     *
     * @return The future for this output.
     */
    @Override
    public synchronized CompletableFuture<T> getFuture() {
        if (future == null) {
            future = getTask().taskFuture().thenApply(e -> get());
        }
        return future;
    }

    /**
     * Get the value stored in this output now.
     * <p>
     * This will not wait for the task to be complete, and should
     * be used by tasks internally.
     * <p>
     * This can only be used by outputs which are not {@link #isComputed} or have had their value set.
     *
     * @return The value in this output.
     */
    @Override
    public T get() {
        return Objects.requireNonNull(value, "Output value has not been set yet.");
    }

    /**
     * Set the value of this output.
     * <p>
     * It is illegal to override the output if the task has already been scheduled, and this output is not {@link #isComputed}.
     *
     * @param value The value.
     */
    public void set(T value) {
        if (task.isFutureResolved() && !isComputed()) {
            throw new IllegalStateException("Unable to set Output value after task execution has been scheduled.");
        }
        this.value = value;
    }

    @Override
    boolean isValueSet() {
        return value != null;
    }

    /**
     * @return If this task output value is set when the task executes.
     */
    public boolean isComputed() {
        return isComputed;
    }
}
