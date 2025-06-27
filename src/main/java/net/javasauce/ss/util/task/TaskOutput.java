package net.javasauce.ss.util.task;

import java.util.function.Function;

/**
 * An output for a task.
 * <p>
 * Task outputs may be declared as dynamic, indicating that their output is set
 * as a result of the task being executed.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public final class TaskOutput<T> extends TaskIO<T> {

    private final boolean isDynamic;

    TaskOutput(Task task, String name, boolean isDynamic) {
        super(task, name);
        this.isDynamic = isDynamic;
    }

    /**
     * Derive this tasks output from the output of another task.
     * <p>
     * This will declare the other task as a dependency on this task.
     *
     * @param output The output to derive from.
     * @param func   The function to derive the output. Guaranteed to run only after the other task has executed.
     */
    public <U> void deriveFrom(TaskOutput<? extends U> output, Function<? super U, ? extends T> func) {
        set(() -> output.getTask().taskFuture().thenCompose(e -> output.getFuture()).thenApply(func));
    }

    /**
     * @return If this task output is dynamic.
     */
    public boolean isDynamic() {
        return isDynamic;
    }

    @Override
    protected void validateSetPreconditions() {
        if (!isDynamic()) {
            super.validateSetPreconditions();
        }
    }
}
