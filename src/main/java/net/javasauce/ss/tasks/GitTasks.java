package net.javasauce.ss.tasks;

import net.javasauce.ss.util.VersionManifest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Created by covers1624 on 1/21/25.
 */
public class GitTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitTasks.class);

    public static Git initRepo(Path versionDir, String gitRemote, VersionManifest manifest) {
        String branch = manifest.type() + "/" + manifest.id();
        LOGGER.info("Setting up git branch {} in {}", branch, versionDir);
        try {
            var git = Git.init()
                    .setDirectory(versionDir.toFile())
                    .setInitialBranch(branch)
                    .call();
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(gitRemote))
                    .call();
            git.fetch()
                    .setProgressMonitor(new TextProgressMonitor())
                    .call();

            if (git.getRepository().findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                LOGGER.info("Pulling existing branch..");
                git.pull()
                        .setRemoteBranchName(branch)
                        .call();
            } else {
                LOGGER.info("Setting remote tracking for new branch.");
                // Thanks jgit for not just having this.
                // In theory, we can do a branch create force, but this seems more sane.
                StoredConfig config = git.getRepository().getConfig();
                config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, "origin");
                config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE, Constants.R_HEADS + branch);
                config.save();
            }

            return git;
        } catch (IOException | GitAPIException | URISyntaxException ex) {
            throw new RuntimeException("Failed to init git repo.", ex);
        }
    }

    public static void stageAndCommit(Git git, String message) {
        LOGGER.info("Committing changes.");
        try {
            git.add()
                    .addFilepattern(".")
                    .call();
            git.commit()
                    .setAuthor("SnowShovel", "snowshovel@javasauce.net")
                    .setAllowEmpty(true)
                    .setMessage(message)
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to stage and commit changes.", ex);
        }

    }

    public static void pushChanges(Git git) {
        LOGGER.info("Pushing changes..");
        try {
            git.push()
                    .setProgressMonitor(new TextProgressMonitor())
                    .call();
        } catch (GitAPIException ex) {
            throw new RuntimeException("Failed to push changes.", ex);
        }
    }
}
