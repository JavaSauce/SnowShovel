package net.javasauce.ss.util.task;

import net.covers1624.quack.collection.FastStream;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An input for a Task.
 * <p>
 * Created by covers1624 on 6/26/25.
 */
public sealed class TaskInput<T> extends TaskIO<T> permits TaskInput.Collection {

    TaskInput(Task task, String name) {
        super(task, name);
    }

    /**
     * Set the value of this input to be the value of the provided
     * task output, once it's complete.
     * <p>
     * This will declare a dependency between the tasks.
     *
     * @param output The output.
     */
    public void set(TaskOutput<T> output) {
        set(output::getFuture, List.of(output.getTask()));
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
        // TODO this should probably just be List, not Collection
        public void set(java.util.Collection<TaskOutput<E>> outputs) {
            set(() -> {
                var futures = FastStream.of(outputs)
                        .map(TaskIO::getFuture)
                        .toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(futures)
                        .thenApply(e -> FastStream.of(futures)
                                .map(CompletableFuture::join)
                                .toList());
            }, FastStream.of(outputs)
                    .map(TaskIO::getTask)
                    .toList());
        }
    }
}
