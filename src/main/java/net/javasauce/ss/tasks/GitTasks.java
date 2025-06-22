package net.javasauce.ss.tasks;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.util.SneakyUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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

    public static List<TagEntry> listAllTags(Git git) {
        try {
            var refDb = git.getRepository().getRefDatabase();
            List<TagEntry> entries = new ArrayList<>();
            for (Ref ref : git.tagList().call()) {
                var peeled = refDb.peel(ref);
                entries.add(new TagEntry(
                        Repository.shortenRefName(ref.getName()),
                        peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId().getName() : peeled.getObjectId().getName())
                );
            }
            return entries;
        } catch (GitAPIException | IOException ex) {
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

    public static void fastForwardBranchToCommit(Git git, String branch, String commit) {
        try {
            var repo = git.getRepository();
            // We are already on the branch, and HEAD is already the commit we want.
            if (repo.getBranch().equals(branch) && repo.resolve(Constants.HEAD).getName().equals(commit)) return;

            if (repo.findRef(Constants.R_HEADS + branch) != null) {
                LOGGER.info("Checking out existing branch {}", branch);
                git.checkout()
                        .setName(branch)
                        .call();
                fastForwardBranch(git, commit);
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
                fastForwardBranch(git, commit);
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

    private static void fastForwardBranch(Git git, String commit) throws IOException, GitAPIException {
        LOGGER.info("Fast-Forwarding to {}", commit);
        var repo = git.getRepository();
        var merge = git.merge()
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .include(repo.resolve(commit))
                .call();
        if (!merge.getMergeStatus().isSuccessful()) {
            throw new RuntimeException("Unable to fast forward branch. " + merge.getMergeStatus());
        }
    }

    public static String stageAndCommit(Git git, String message) {
        LOGGER.info("Committing changes.");
        stageChanges(git);
        try {
            var rev = git.commit()
                    .setAuthor("SnowShovel", "snowshovel@javasauce.net")
                    .setCommitter("SnowShovel", "snowshovel@javasauce.net")
                    .setAllowEmpty(true)
                    .setMessage(message)
                    .call();
            return rev.getId().getName();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to commit changes.", ex);
        }
    }

    public static String stageAndAmend(Git git) {
        LOGGER.info("Amending changes.");
        stageChanges(git);
        var repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            var rev = git.commit()
                    .setAmend(true)
                    .setMessage(walk.parseCommit(repo.resolve(Constants.HEAD)).getFullMessage())
                    .call();
            return rev.getId().getName();
        } catch (GitAPIException | IOException ex) {
            throw new RuntimeException("Failed to amend changes.", ex);
        }
    }

    public static void stageChanges(Git git) {
        LOGGER.info("Staging changes.");
        try {
            git.add()
                    .addFilepattern(".")
                    .call();
            git.add()
                    .addFilepattern(".")
                    .setUpdate(true)
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Unable to stage changes.", ex);
        }
    }

    public static void createTag(Git git, String tag) {
        LOGGER.info("Creating tag {}.", tag);
        try {
            git.tag()
                    .setName(tag)
                    .setForceUpdate(true)
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to create tag.", ex);
        }
    }

    public static void deleteTags(Git git, List<String> tags) {
        LOGGER.info("Deleting local tags.");
        try {
            git.tagDelete()
                    .setTags(tags.toArray(String[]::new))
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to delete tags.", ex);
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

    public static void pushAllTags(Git git) {
        LOGGER.info("Pushing tags..");
        try {
            git.push()
                    .setRemote("origin")
                    .setPushTags()
                    .setProgressMonitor(new TextProgressMonitor())
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to push changes.", ex);
        }
    }

    public static void pushDeleteTags(Git git, List<String> tags) {
        LOGGER.info("Pushing tag deletions.");
        try {
            git.push()
                    .setRemote("origin")
                    .setRefSpecs(FastStream.of(tags)
                            .map(e -> new RefSpec(":refs/tags" + e))
                            .toList()
                    )
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to push tag deletions.", ex);
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

    public record TagEntry(String name, String commit) { }
}
