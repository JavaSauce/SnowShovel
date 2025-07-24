package net.javasauce.ss.tasks.git;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.util.task.TaskInput;
import org.eclipse.jgit.transport.RefSpec;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/24/25.
 */
public class DeleteTagsTask extends AbstractGitTask {

    public final TaskInput<List<String>> tagNames = input("tagNames");

    public final TaskInput<Boolean> local = input("remote", false);
    public final TaskInput<Boolean> remote = input("remote", false);

    private DeleteTagsTask(String name, Executor executor) {
        super(name, executor);
    }

    public static DeleteTagsTask create(String name, Executor executor, Consumer<DeleteTagsTask> cons) {
        var task = new DeleteTagsTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var git = this.git.get();
        var tagNames = this.tagNames.get();
        var local = this.local.get();
        var remote = this.remote.get();
        if (!local && !remote) throw new IllegalArgumentException("Expected one or both of local/remote.");

        if (local) {
            git.tagDelete()
                    .setTags(tagNames.toArray(String[]::new))
                    .call();
        }
        if (remote) {
            git.push()
                    .setRemote("origin")
                    .setForce(true)
                    .setRefSpecs(FastStream.of(tagNames)
                            .map(e -> new RefSpec(":refs/tags/" + e))
                            .toList()
                    )
                    .call();
        }
    }
}
