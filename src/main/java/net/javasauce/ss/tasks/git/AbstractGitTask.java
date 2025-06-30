package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executor;

/**
 * Created by covers1624 on 6/29/25.
 */
public abstract class AbstractGitTask extends Task {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    public final TaskInput<Git> git = input("git");

    protected AbstractGitTask(String name, Executor executor) {
        super(name, executor);
    }

    protected void checkoutOrCreateBranch(String branch) throws GitAPIException, IOException {
        var git = this.git.get();
        if (git.getRepository().findRef(Constants.R_HEADS + branch) != null) {
            LOGGER.info("Checking out existing branch {}", branch);
            git.checkout()
                    .setName(branch)
                    .call();
            return;
        }
        if (git.getRepository().findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
            LOGGER.info("Checking out existing remote branch {}", branch);
            git.checkout()
                    .setName(branch)
                    .setCreateBranch(true)
                    .setStartPoint("origin/" + branch)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
            return;
        }
        LOGGER.info("Creating new branch {}", branch);
        git.checkout()
                .setName(branch)
                .setOrphan(true)
                .call();
    }

    protected void stageChanges() throws GitAPIException {
        LOGGER.info("Staging changes.");
        git.get().add()
                .addFilepattern(".")
                .call();
        git.get().add()
                .addFilepattern(".")
                .setUpdate(true)
                .call();
    }

    protected void commitChanges(String message) throws GitAPIException {
        LOGGER.info("Committing changes with message {}", message);
        git.get().commit()
                .setAuthor("SnowShovel", "snowshovel@javasauce.net")
                .setCommitter("SnowShovel", "snowshovel@javasauce.net")
                .setAllowEmpty(true)
                .setMessage(message)
                .call();
    }

    protected void createTag(String tag) throws GitAPIException {
        LOGGER.info("Creating tag {}", tag);
        git.get().tag()
                .setName(tag)
                .setForceUpdate(true)
                .call();
    }

    protected void wipeCheckedOutFiles() throws IOException {
        var git = this.git.get();
        var gitDir = git.getRepository().getDirectory().toPath();
        var repoDir = gitDir.getParent();
        Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Don't descend into the .git directory.
                if (dir.equals(gitDir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                // We have reached the starting directory, just terminate.
                if (dir.equals(repoDir)) {
                    return FileVisitResult.TERMINATE;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
