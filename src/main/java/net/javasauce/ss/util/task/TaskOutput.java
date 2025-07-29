package net.javasauce.ss.util.task;

import net.javasauce.ss.util.MemoizedSupplier;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An output for a task.
 * <p>
 * Task outputs may be declared as computed, indicating that their output is set
 * as a result of the task being executed.
 * <p>
 * Task outputs may be derived from the values in other inputs or outputs, these are always resolved and set
 * before the Output's task is executed.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public final class TaskOutput<T> extends TaskIO<T> {

    private final boolean isComputed;

    private @Nullable MemoizedSupplier<CompletableFuture<T>> derivedFuture;
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
            future = getTask().getFuture().thenApply(e -> get());
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

    /**
     * Derive this output's value from the input or output of another task, with the given
     * function applied to the result.
     * <p>
     * Derived outputs are always resolved before the current task is run. If you provide Inputs for derivation
     * it will not mark the task they are from as a dependency, only any task that input depends on will be marked
     * dependencies. Conversely, if you provide an output, that task will become a dependency.
     *
     * @param aIo  The IO to derive from.
     * @param func The function to apply
     */
    public <A> void deriveFrom(TaskIO<? extends A> aIo, Function<? super A, ? extends T> func) {
        if (isComputed()) throw new UnsupportedOperationException("Currently can't use deriveFrom for computed outputs.");
        if (task.isFutureResolved()) throw new IllegalStateException("Unable to set Output value after task execution has been scheduled.");

        derivedFuture = new MemoizedSupplier<>(() -> aIo.getFuture().thenApply(e -> {
            value = func.apply(e);
            return value;
        }));
    }

    /**
     * Derive this output's value from multiple inputs and/or outputs of other tasks, with the given
     * function applied to the result.
     * <p>
     * Derived outputs are always resolved before the current task is run. If you provide Inputs for derivation
     * it will not mark the task they are from as a dependency, only any task that input depends on will be marked
     * dependencies. Conversely, if you provide an output, that task will become a dependency.
     *
     * @param aIo  The IO to derive from.
     * @param func The function to apply
     */
    public <A, B> void deriveFrom(TaskIO<? extends A> aIo, TaskIO<? extends B> bIo, BiFunction<? super A, ? super B, ? extends T> func) {
        if (isComputed()) throw new UnsupportedOperationException("Currently can't use deriveFrom for computed outputs.");
        if (task.isFutureResolved()) throw new IllegalStateException("Unable to set Output value after task execution has been scheduled.");

        derivedFuture = new MemoizedSupplier<>(() -> aIo.getFuture().thenCombine(bIo.getFuture(), (a, b) -> {
            value = func.apply(a, b);
            return value;
        }));
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

    /**
     * @return If this Output is a derived output, retrieves the future to compute the
     * derived output, otherwise null.
     */
    @Nullable CompletableFuture<?> deriveFuture() {
        return derivedFuture != null ? derivedFuture.get() : null;
    }
}
