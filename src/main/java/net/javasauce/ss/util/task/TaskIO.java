package net.javasauce.ss.util.task;

import net.javasauce.ss.util.MemoizedSupplier;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Represents some task input or output.
 * <p>
 * TaskIO's are a wrapper around a Memoized supplier, producing a CompletableFuture (effectively a LazyCompletableFuture),
 * and a list of required tasks for the future result.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public abstract sealed class TaskIO<T> implements Supplier<T> permits TaskInput, TaskOutput {

    protected final Task task;
    private final String name;

    // We use a supplier to avoid resolving a tasks output too early.
    protected @Nullable Supplier<CompletableFuture<T>> futureSupplier;

    protected TaskIO(Task task, String name) {
        this.task = task;
        this.name = name;
    }

    /**
     * Resolve the future for this IO and return it.
     *
     * @return The resolved future.
     */
    public final synchronized CompletableFuture<T> getFuture() {
        if (futureSupplier == null) {
            throw new IllegalStateException("IO " + name + " of task " + task.getName() + " has not had a value assigned.");
        }
        return futureSupplier.get();
    }

    /**
     * Get the value stored in this TaskIO.
     * <p>
     * This will block on the future if it is not complete.
     *
     * @return The value.
     */
    @Override
    public final T get() {
        return getFuture().join();
    }

    /**
     * Set the IO future supplier.
     *
     * @param futureSupplier The supplier to provide the future for this IO's value.
     */
    protected final void set(Supplier<CompletableFuture<T>> futureSupplier) {
        validateSetPreconditions();
        this.futureSupplier = new MemoizedSupplier<>(futureSupplier);
    }

    /**
     * Set the IO to a static value.
     *
     * @param thing The value to set.
     */
    public final void set(T thing) {
        set(() -> CompletableFuture.completedFuture(thing));
    }

    protected void validateSetPreconditions() {
        if (task.isFutureResolved()) {
            throw new IllegalStateException("Unable to set IO value after task execution has been scheduled.");
        }
    }

    public final Task getTask() {
        return task;
    }

    public final String getName() {
        return name;
    }

    final boolean isValueSet() {
        return futureSupplier != null;
    }
}
