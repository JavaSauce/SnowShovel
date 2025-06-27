package net.javasauce.ss.util.task;

import java.util.List;
import java.util.function.Function;

/**
 * An output for a task.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
// TODO we need a way to declare outputs as dynamic (set as a result of task execution), in addition
//  to outputs which are static and supplied when the test is setup.
public final class TaskOutput<T> extends TaskIO<T> {

    TaskOutput(Task task, String name) {
        super(task, name);
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
        set(() -> output.getFuture().thenApply(func), List.of(output.getTask()));
    }
}
