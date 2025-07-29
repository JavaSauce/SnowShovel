package net.javasauce.ss.util.task;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.util.SneakyUtils;
import net.javasauce.ss.util.MemoizedSupplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A task is some operation that executes on a given executor. Tasks can declare
 * inputs and outputs. Outputs of one task may be used as inputs to another task.
 * <p>
 * Tasks automatically declare dependencies between each-other by consuming their outputs
 * as inputs, or deriving their own output from another tasks output.
 * Note: Task dependencies are collected when a dependent task is scheduled for execution.
 * <p>
 * Tasks may declare themselves as cacheable, using a selection of their inputs
 * and outputs as the cache value, if the cache value matches the previous run, it will
 * not be re-run. Note: It's important to ensure your outputs are included in the cache,
 * otherwise your task may not re-run when it's missing.
 * <p>
 * Tasks may declare 'inner' tasks via {@link #declareCompositeTask}, these tasks copy any
 * explicit dependencies their outer has/will have, and are marked as explicit dependencies of their outer.
 * <p>
 * Created by covers1624 on 6/24/25.
 */
public abstract class Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);

    private final List<TaskInput<?>> inputs = new ArrayList<>();
    private final List<TaskOutput<?>> outputs = new ArrayList<>();

    private final String name;
    private final Executor executor;

    private @Nullable CompletableFuture<Task> taskFuture;
    private @Nullable Supplier<TaskCacheBuilder> cache;

    private final List<Task> dependsOn = new ArrayList<>();
    private final List<Task> innerTasks = new ArrayList<>();

    /**
     * @param name     The name for this task, used for logging.
     * @param executor The executor to run this task on. If you don't know what to provide
     *                 you can use {@link ForkJoinPool#commonPool()}, for the built-in executor.
     *                 Providing your task a specific executor can be used to create execution
     *                 groups, for example, using a single-thread executor can ensure only one
     *                 instance of your task is executed at a time.
     */
    public Task(String name, Executor executor) {
        this.name = name;
        this.executor = executor;
    }

    /**
     * Schedule the given tasks and wait for them to complete.
     *
     * @param tasks The tasks to run.
     */
    public static void runTasks(Task... tasks) {
        runTasks(Arrays.asList(tasks));
    }

    /**
     * Schedule the given tasks and wait for them to complete.
     *
     * @param tasks The tasks to run.
     */
    public static void runTasks(Iterable<? extends Task> tasks) {
        var futures = FastStream.of(tasks)
                .map(Task::getFuture)
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }

    /**
     * Enable caching for your task.
     *
     * @param cacheNextTo The output to store the cache file next to.
     * @param cons        The function to configure the cache with your cache inputs/outputs.
     */
    protected final void withCaching(TaskOutput<Path> cacheNextTo, Consumer<TaskCacheBuilder> cons) {
        withCaching(cacheNextTo, "", cons);
    }

    /**
     * Enable caching for your task.
     *
     * @param cacheNextTo The output to store the cache file next to.
     * @param cacheSuffix A suffix on the cache name, this can be used to distinguish between .sha1 files. // TODO perhaps we change the suffix to always be '_task.sha1'
     * @param configure   The function to configure the cache with your cache inputs/outputs.
     */
    protected final void withCaching(TaskOutput<Path> cacheNextTo, String cacheSuffix, Consumer<TaskCacheBuilder> configure) {
        cache = new MemoizedSupplier<>(() -> {
            var outputPath = cacheNextTo.get();
            var cache = new TaskCacheBuilder(outputPath.resolveSibling(outputPath.getFileName() + cacheSuffix + ".sha1"));
            configure.accept(cache);
            return cache;
        });
    }

    /**
     * Create a new input for your task. You must set a value before
     * the task executes.
     *
     * @param name The name for your input. Used in logging/errors.
     * @return A new input for your task, a value must be set.
     */
    protected final <T> TaskInput<T> input(String name) {
        var input = new TaskInput<T>(this, name);
        inputs.add(input);
        return input;
    }

    /**
     * Create a new input for your task, with the provided initial value.
     *
     * @param name The name for your input. Used in logging/errors.
     * @return A new input for your task.
     */
    protected final <T> TaskInput<T> input(String name, T _default) {
        var input = new TaskInput<T>(this, name);
        input.set(_default);
        inputs.add(input);
        return input;
    }

    /**
     * Create a new optional input for your task. The default value
     * is set to empty.
     *
     * @param name The name for your input. Used in logging/errors.
     * @return A new input for your task.
     */
    protected final <T> TaskInput<Optional<T>> optionalInput(String name) {
        var input = new TaskInput<Optional<T>>(this, name);
        input.set(Optional.empty());
        inputs.add(input);
        return input;
    }

    /**
     * Create a new collection input for your task. The default value
     * is set to an empty list.
     *
     * @param name The name for your input. Used in logging/errors.
     * @return A new input for your task.
     */
    protected final <T> TaskInput.Collection<T> inputCollection(String name) {
        var input = new TaskInput.Collection<T>(this, name);
        input.set(List.<T>of());
        inputs.add(input);
        return input;
    }

    /**
     * Create a new output for your task. You must set a value
     * before executing the task.
     *
     * @param name The name for your output. Used in logging/errors.
     * @return A new output for your task.
     */
    protected final <T> TaskOutput<T> output(String name) {
        var output = new TaskOutput<T>(this, name, false);
        outputs.add(output);
        return output;
    }

    /**
     * Create a new output for your task. You must set a value for this
     * output before your task finishes executing.
     *
     * @param name The name for your output. Used in logging/errors.
     * @return A new output for your task.
     */
    protected final <T> TaskOutput<T> computedOutput(String name) {
        var output = new TaskOutput<T>(this, name, true);
        outputs.add(output);
        return output;
    }

    /**
     * Mark this task as requiring the provided tasks, without
     * declaring any IO dependencies.
     *
     * @param tasks The tasks to depend on.
     */
    public void dependsOn(Task... tasks) {
        dependsOn(Arrays.asList(tasks));
    }

    /**
     * Mark this task as requiring the provided tasks, without
     * declaring any IO dependencies.
     *
     * @param tasks The tasks to depend on.
     */
    public void dependsOn(Iterable<Task> tasks) {
        tasks.forEach(dependsOn::add);

        innerTasks.forEach(task -> task.dependsOn(tasks));
    }

    /**
     * Declare the given task execute before this task.
     * <p>
     * The given task will copy all explicit dependencies of this task, however,
     * will not automatically copy any implicit dependencies handed through IO variables.
     * The given task will need to either derive its own inputs from the inputs of the outer, or
     * copy the outers inputs.
     */
    //TODO, we probably need to have an easy safeguard to prevent the inner task declaring a dependency on
    //      the outer task via its outputs, as this will create a deadlock.
    protected void declareCompositeTask(Task task) {
        task.dependsOn(dependsOn);
        innerTasks.add(task);
    }

    /**
     * Resolve this tasks dependencies and return a future for the execution of this task.
     *
     * @return The future.
     */
    public synchronized final CompletableFuture<Task> getFuture() {
        if (taskFuture == null) {
            var inputFuture = CompletableFuture.allOf(
                    FastStream.concat(
                                    FastStream.of(dependsOn).map(Task::getFuture),
                                    FastStream.of(innerTasks).map(Task::getFuture),
                                    FastStream.of(outputs).map(TaskOutput::deriveFuture).filter(Objects::nonNull),
                                    FastStream.of(inputs).map(TaskIO::getFuture)
                            )
                            .toArray(CompletableFuture[]::new)
            );
            taskFuture = inputFuture.thenApplyAsync(v -> {
                try {
                    doExecute();
                } catch (Throwable ex) {
                    SneakyUtils.throwUnchecked(ex);
                }
                return this;
            }, executor);
        }
        return taskFuture;
    }

    private void doExecute() throws Throwable {
        LOGGER.info("Executing task {}", name);
        for (TaskInput<?> input : inputs) {
            if (!input.isValueSet()) {
                throw new IllegalStateException("Input '" + input.getName() + "' for task '" + getName() + "' must have a value set.");
            }
        }
        for (TaskOutput<?> output : outputs) {
            if (!output.isComputed() && !output.isValueSet()) {
                throw new IllegalStateException("Output '" + output.getName() + "' for task '" + getName() + "' must have a value set before its executed. Did you want a computed output instead?.");
            }
        }
        TaskCacheBuilder cache = this.cache != null ? this.cache.get() : null;
        if (cache != null && cache.isUpToDate()) {
            LOGGER.info("Skipping task {}, is up-to-date.", name);
            return;
        }
        execute();
        for (TaskOutput<?> output : outputs) {
            if (output.isComputed() && !output.isValueSet()) {
                throw new IllegalStateException("Output '" + output.getName() + "' for task '" + getName() + "' did not produce a required output.");
            }
        }

        if (cache != null) {
            cache.writeCache();
        }
        LOGGER.info("Task {} finished.", name);
    }

    /**
     * Called to execute your task actions.
     */
    protected abstract void execute() throws Throwable;

    public final String getName() {
        return name;
    }

    final boolean isFutureResolved() {
        return taskFuture != null;
    }
}
