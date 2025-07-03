package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.TaskInput;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/30/25.
 */
public class PushAllTask extends AbstractGitTask {

    public final TaskInput<Boolean> tags = input("tags", false);
    public final TaskInput<Boolean> branches = input("branches", false);

    private PushAllTask(String name, Executor executor) {
        super(name, executor);
    }

    public static PushAllTask create(String name, Executor executor, Consumer<PushAllTask> cons) {
        var task = new PushAllTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var tags = this.tags.get();
        var branches = this.branches.get();
        if (!tags && !branches) {
            throw new RuntimeException("PushAllTask requires at least one of 'tags' or 'branches' to be set to true.");
        }

        if (tags) pushAllTags();
        if (branches) pushAllBranches();
    }
}
