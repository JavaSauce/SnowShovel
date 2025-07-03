package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.TaskInput;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/30/25.
 */
public class FastForwardTask extends AbstractGitTask {

    public final TaskInput<String> branch = input("branch");
    public final TaskInput<Optional<String>> tag = optionalInput("tag");
    public final TaskInput<Optional<String>> commit = optionalInput("commit");

    private FastForwardTask(String name, Executor executor) {
        super(name, executor);
    }

    public static FastForwardTask create(String name, Executor executor, Consumer<FastForwardTask> cons) {
        var task = new FastForwardTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var tag = this.tag.get().orElse(null);
        var commit = this.commit.get().orElse(null);

        if ((tag == null) == (commit == null)) {
            throw new RuntimeException("One of tag or commit must be set.");
        }

        if (tag != null) {
            commit = listAllTags().get(tag);
            if (commit == null) {
                throw new RuntimeException("Tag " + tag + " does not exist.");
            }
        }

        fastForwardBranchToCommit(branch.get(), commit);
    }
}
