package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.TaskInput;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/29/25.
 */
public class CommitAndTagTask extends AbstractGitTask {

    // TODO make this optional and have another option to amend?
    public final TaskInput<String> commitMessage = input("commitMessage");
    public final TaskInput<String> tagName = input("tagName");

    private CommitAndTagTask(String name, Executor executor) {
        super(name, executor);
    }

    public static CommitAndTagTask create(String name, Executor executor, Consumer<CommitAndTagTask> cons) {
        var task = new CommitAndTagTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        stageChanges();
        commitChanges(commitMessage.get());
        createTag(tagName.get());
    }
}
