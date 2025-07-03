package net.javasauce.ss.tasks.git;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.util.SneakyUtils;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
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

    protected void pushAllBranches() throws GitAPIException {
        git.get().push()
                .setRemote("origin")
                .setPushAll()
                .setProgressMonitor(new TextProgressMonitor())
                .call();
    }

    protected void pushAllTags() throws GitAPIException {
        LOGGER.info("Pushing all tags...");
        git.get().push()
                .setRemote("origin")
                .setForce(true)
                .setPushTags()
                .setProgressMonitor(new TextProgressMonitor())
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

    protected <T> @Nullable T loadBlob(String object, SneakyUtils.ThrowingFunction<ObjectStream, ? extends T, IOException> func) throws IOException {
        var git = this.git.get();
        var blobId = git.getRepository().resolve(object);
        if (blobId == null) return null;

        var loader = git.getRepository().open(blobId);
        try (var stream = loader.openStream()) {
            return func.apply(stream);
        }
    }

    protected Map<String, String> listAllTags() {
        try {
            var git = this.git.get();
            var refDb = git.getRepository().getRefDatabase();
            Map<String, String> entries = new HashMap<>();
            for (Ref ref : git.tagList().call()) {
                var peeled = refDb.peel(ref);
                entries.put(
                        Repository.shortenRefName(ref.getName()),
                        peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId().getName() : peeled.getObjectId().getName()
                );
            }
            return entries;
        } catch (GitAPIException | IOException ex) {
            throw new RuntimeException("Failed to list all branches.", ex);
        }
    }

    protected Map<String, String> listAllBranches() throws GitAPIException {
        return FastStream.of(git.get().branchList()
                        .setListMode(ListBranchCommand.ListMode.ALL)
                        .call())
                .filter(e -> e.getObjectId() != null)
                .toMap(e -> Repository.shortenRefName(e.getName()), e -> e.getObjectId().getName());
    }

    protected void fastForwardBranchToCommit(String branch, String commit) {
        var git = this.git.get();
        try {
            var repo = git.getRepository();
            // We are already on the branch, and HEAD is already the commit we want.
            if (repo.getBranch().equals(branch) && repo.resolve(Constants.HEAD).getName().equals(commit)) return;

            if (repo.findRef(Constants.R_HEADS + branch) != null) {
                LOGGER.info("Checking out existing branch {}", branch);
                git.checkout()
                        .setName(branch)
                        .call();
                fastForwardBranch(commit);
                return;
            }
            if (repo.findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                LOGGER.info("Checking out existing remote branch {}", branch);
                git.checkout()
                        .setName(branch)
                        .setCreateBranch(true)
                        .setStartPoint("origin/" + branch)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .call();
                fastForwardBranch(commit);
                return;
            }

            LOGGER.info("Creating new branch {}", branch);
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branch)
                    .setStartPoint(commit)
                    .call();
        } catch (IOException | GitAPIException ex) {
            throw new RuntimeException("Failed to reset branch " + branch + " to commit " + commit, ex);
        }
    }

    protected void fastForwardBranch(String commit) throws IOException, GitAPIException {
        LOGGER.info("Fast-Forwarding to {}", commit);
        var git = this.git.get();
        var repo = git.getRepository();
        var merge = git.merge()
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .include(repo.resolve(commit))
                .call();
        if (!merge.getMergeStatus().isSuccessful()) {
            throw new RuntimeException("Unable to fast forward branch. " + merge.getMergeStatus());
        }
    }
}
