package net.javasauce.ss.util.task;

import net.javasauce.ss.util.MemoizedSupplier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final List<Task> dependencies = new ArrayList<>();

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
     * Set the IO future supplier, and its required dependencies.
     *
     * @param futureSupplier The supplier to provide the future for this IO's value.
     * @param dependencies   The tasks required to provide the future its value.
     */
    protected final void set(Supplier<CompletableFuture<T>> futureSupplier, List<Task> dependencies) {
        this.futureSupplier = new MemoizedSupplier<>(futureSupplier);
        this.dependencies.clear();
        this.dependencies.addAll(dependencies);
    }

    /**
     * Set the IO future supplier.
     *
     * @param futureSupplier The supplier to provide the future for this IO's value.
     */
    public final void set(Supplier<CompletableFuture<T>> futureSupplier) {
        set(futureSupplier, List.of());
    }

    /**
     * Set the IO to a static value.
     *
     * @param thing The value to set.
     */
    public final void set(T thing) {
        set(() -> CompletableFuture.completedFuture(thing));
    }

    public final Task getTask() {
        return task;
    }

    public final String getName() {
        return name;
    }

    public final List<Task> getDependencies() {
        return Collections.unmodifiableList(dependencies);
    }
}
