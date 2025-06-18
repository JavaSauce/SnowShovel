package net.javasauce.ss.tasks;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.util.SneakyUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Created by covers1624 on 1/21/25.
 */
public class GitTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitTasks.class);

    public static Git setup(Path dir, String gitRepo) {
        LOGGER.info("Setting up checkout of {} in {}", gitRepo, dir);
        try {
            Git git;
            if (Files.exists(dir)) {
                git = Git.open(dir.toFile());
            } else {
                git = Git.cloneRepository()
                        .setDirectory(dir.toFile())
                        .setURI(gitRepo)
                        .setNoCheckout(true)
                        .setProgressMonitor(new TextProgressMonitor())
                        .call();
            }
            checkoutOrCreateBranch(git, "main");
            return git;
        } catch (GitAPIException | IOException ex) {
            throw new RuntimeException("Failed to init git repo.", ex);
        }
    }

    public static List<BranchEntry> listAllBranches(Git git) {
        try {
            return FastStream.of(git.branchList()
                            .setListMode(ListBranchCommand.ListMode.ALL)
                            .call())
                    .filter(e -> e.getObjectId() != null)
                    .map(e -> new BranchEntry(Repository.shortenRefName(e.getName()), e.getObjectId().getName()))
                    .toList();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to list all branches.", ex);
        }
    }

    public static void loadBlob(Git git, String object, SneakyUtils.ThrowingConsumer<ObjectStream, IOException> func) throws IOException {
        var blobId = git.getRepository().resolve(object);
        if (blobId == null) return;

        var loader = git.getRepository().open(blobId);
        try (var stream = loader.openStream()) {
            func.accept(stream);
        }
    }

    public static boolean checkoutOrCreateBranch(Git git, String branch) {
        try {
            if (git.getRepository().getBranch().equals(branch)) return false;

            if (git.getRepository().findRef(Constants.R_HEADS + branch) != null) {
                LOGGER.info("Checking out existing branch {}", branch);
                git.checkout()
                        .setName(branch)
                        .call();
                return false;
            }
            if (git.getRepository().findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                LOGGER.info("Checking out existing remote branch {}", branch);
                git.checkout()
                        .setName(branch)
                        .setCreateBranch(true)
                        .setStartPoint("origin/" + branch)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .call();
                return false;
            }
            LOGGER.info("Creating new branch {}", branch);
            git.checkout()
                    .setName(branch)
                    .setOrphan(true)
                    .call();
            return true;
        } catch (IOException | GitAPIException ex) {
            throw new RuntimeException("Failed to checkout branch " + branch, ex);
        }
    }

    public static String stageAndCommit(Git git, String message) {
        LOGGER.info("Committing changes.");
        try {
            git.add()
                    .addFilepattern(".")
                    .call();
            var rev = git.commit()
                    .setAuthor("SnowShovel", "snowshovel@javasauce.net")
                    .setCommitter("SnowShovel", "snowshovel@javasauce.net")
                    .setAllowEmpty(true)
                    .setMessage(message)
                    .call();
            return rev.getId().getName();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to stage and commit changes.", ex);
        }

    }

    public static void pushAllBranches(Git git) {
        LOGGER.info("Pushing changes..");
        try {
            git.push()
                    .setRemote("origin")
                    .setPushAll()
                    .setProgressMonitor(new TextProgressMonitor())
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to push changes.", ex);
        }
    }

    public static void removeAllFiles(Path repoDir) throws IOException {
        Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Don't descend into the .git directory.
                if (dir.equals(repoDir.resolve(".git"))) {
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

    public record BranchEntry(String name, String commit) { }
}
