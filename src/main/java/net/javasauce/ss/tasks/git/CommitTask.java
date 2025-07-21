package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.TaskInput;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/29/25.
 */
public class CommitTask extends AbstractGitTask {

    public final TaskInput<Boolean> amend = input("amend", false);
    public final TaskInput<Optional<String>> commitMessage = optionalInput("commitMessage");
    public final TaskInput<Optional<String>> tagName = optionalInput("tagName");

    private CommitTask(String name, Executor executor) {
        super(name, executor);
    }

    public static CommitTask create(String name, Executor executor, Consumer<CommitTask> cons) {
        var task = new CommitTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        stageChanges();
        if (amend.get()) {
            amendChanges(commitMessage.get().orElse(null));
        } else {
            commitChanges(commitMessage.get().orElseThrow(() -> new RuntimeException("Expected commitMessage to exist for non-amends.")));
        }

        var tag = tagName.get().orElse(null);
        if (tag != null) {
            createTag(tag);
        }
    }
}
