package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.DeleteHierarchyVisitor;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/18/25.
 */
public class SetupGitRepoTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupGitRepoTask.class);

    public final TaskInput<Path> repoDir = input("repoDir");
    public final TaskInput<String> repoUrl = input("repoUrl");
    public final TaskInput<Boolean> clearClone = input("clearClone", false);

    public final TaskOutput<Git> output = computedOutput("output");

    private SetupGitRepoTask(String name, Executor executor) {
        super(name, executor);
    }

    public static SetupGitRepoTask create(String name, Executor executor, Consumer<SetupGitRepoTask> cons) {
        var task = new SetupGitRepoTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var repoDir = this.repoDir.get();
        var repoUrl = this.repoUrl.get();
        if (clearClone.get() && Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }

        LOGGER.info("Setting up checkout of {} in {}", repoUrl, repoDir);
        try {
            Git git;
            if (Files.exists(repoDir)) {
                git = Git.open(repoDir.toFile());
            } else {
                git = Git.cloneRepository()
                        .setDirectory(repoDir.toFile())
                        .setURI(repoUrl)
                        .setNoCheckout(true)
                        .setProgressMonitor(new TextProgressMonitor())
                        .call();
            }
            output.set(git);
        } catch (GitAPIException | IOException ex) {
            throw new RuntimeException("Failed to init git repo.", ex);
        }
    }
}
