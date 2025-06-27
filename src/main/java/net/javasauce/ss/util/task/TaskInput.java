package net.javasauce.ss.util.task;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.util.MemoizedSupplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An input for a Task, these are represented as lazy futures.
 * <p>
 * Inputs may be defined as a static known value, or defined as the output
 * of another task.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public sealed class TaskInput<T> extends TaskIO<T> permits TaskInput.Collection {

    // We use a supplier to avoid resolving a tasks output too early.
    private @Nullable Supplier<CompletableFuture<T>> futureSupplier;

    TaskInput(Task task, String name) {
        super(task, name);
    }

    /**
     * Compute the future for this Input, including any tasks required to read the input.
     *
     * @return The future for this input.
     */
    @Override
    public synchronized CompletableFuture<T> getFuture() {
        if (futureSupplier == null) {
            throw new IllegalStateException("IO " + getName() + " of task " + task.getName() + " has not had a value assigned.");
        }
        return futureSupplier.get();
    }

    /**
     * Get the value stored in this Input.
     * <p>
     * Will block until the inputs future is completed.
     *
     * @return The vlaue.
     */
    @Override
    public T get() {
        return getFuture().join();
    }

    /**
     * Set the vlue of this input to a static value.
     * <p>
     * It is illegal to override a task Input, if the task has already been scheduled.
     *
     * @param value The value.
     */
    public void set(T value) {
        set(() -> CompletableFuture.completedFuture(value));
    }

    /**
     * Set the value of this input to be the value of the provided
     * task output, once it's complete.
     * <p>
     * This will declare a dependency between the tasks.
     * <p>
     * It is illegal to override a task Input, if the task has already been scheduled.
     *
     * @param output The output.
     */
    public void set(TaskOutput<T> output) {
        set(output::getFuture);
    }

    /**
     * Set the future supplier for this Input.
     * <p>
     * We use suppliers to avoid resolving the future until the task builds its own future,
     * preventing execution of dependencies until our task is scheduled.
     * <p>
     * It is illegal to override a task Input, if the task has already been scheduled.
     *
     * @param futureSupplier The supplier to provide the future for this IO's value.
     */
    protected final void set(Supplier<CompletableFuture<T>> futureSupplier) {
        if (task.isFutureResolved()) {
            throw new IllegalStateException("Unable to set Input value after task execution has been scheduled.");
        }
        this.futureSupplier = new MemoizedSupplier<>(futureSupplier);
    }

    @Override
    boolean isValueSet() {
        return futureSupplier != null;
    }

    /**
     * An input which can represent multiple things, and multiple dependencies.
     */
    public static final class Collection<E> extends TaskInput<List<E>> {

        Collection(Task task, String name) {
            super(task, name);
        }

        /**
         * Set the input to a collection of the supplied outputs.
         * <p>
         * The outputs will be dynamically collected into a List, in the same order
         * they are supplied. This will mark the task as requiring all supplied task outputs.
         *
         * @param outputs The outputs.
         */
        public void set(List<TaskOutput<E>> outputs) {
            set(() -> {
                var futures = FastStream.of(outputs)
                        .map(TaskIO::getFuture)
                        .toList();
                return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .thenApply(e -> FastStream.of(futures)
                                .map(CompletableFuture::join)
                                .toList());
            });
        }
    }
}
