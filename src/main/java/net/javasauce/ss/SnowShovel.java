package net.javasauce.ss;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.covers1624.curl4j.CABundle;
import net.covers1624.curl4j.httpapi.Curl4jHttpEngine;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.*;
import net.javasauce.ss.util.DeleteHierarchyVisitor;
import net.javasauce.ss.util.JdkProvider;
import net.javasauce.ss.util.VersionManifest;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static java.util.List.of;

/**
 * Created by covers1624 on 1/19/25.
 */
public class SnowShovel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowShovel.class);

    public static void main(String[] args) throws IOException {
        Path workDir = Path.of(".").toAbsolutePath().normalize();
        Path versionsDir = workDir.resolve("versions");
        Path librariesDir = workDir.resolve("libraries");
        Path toolsDir = workDir.resolve("tools");

        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(of("h", "help"), "Prints this help").forHelp();

        OptionSpec<String> versionOpt = parser.acceptsAll(of("v", "version"), "Process one or more minecraft versions.")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> gitRepoOpt = parser.acceptsAll(of("r", "repo"), "The git repository to use.")
                .withRequiredArg();

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        List<String> versionIds = optSet.valuesOf(versionOpt);
        if (versionIds.isEmpty()) {
            LOGGER.error("One or more '--version' arguments required.");
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        if (!optSet.has(gitRepoOpt)) {
            LOGGER.error("The '--repo' argument is required.");
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        String gitRepo = optSet.valueOf(gitRepoOpt);
        String gitUser = System.getenv("GIT_USER");
        String gitPass = System.getenv("GIT_PASS");
        if (gitUser == null || gitPass == null) {
            LOGGER.error("GIT_USER and GIT_PASS environment variables are required.");
            System.exit(1);
            return;
        }
        CredentialsProvider.setDefault(new UsernamePasswordCredentialsProvider(gitUser, gitPass));

        LOGGER.info("ShowShovel started for versions {}.", versionIds);

        HttpEngine http = new Curl4jHttpEngine(CABundle.builtIn());
        JdkProvider jdkProvider = new JdkProvider(toolsDir.resolve("jdks/"), http);

        LOGGER.info("Downloading version manifests..");
        List<VersionManifest> manifests = VersionManifestTasks.getVersionManifests(http, versionsDir, versionIds);
        boolean error = false;
        for (VersionManifest manifest : manifests) {
            for (String download : of("client", "client_mappings")) {
                if (!manifest.hasDownload(download)) {
                    LOGGER.error("Version {} does not have a '{}' download.", manifest.id(), download);
                    error = true;
                }
            }
        }
        if (error) {
            LOGGER.error("One or more errors validating selected versions.");
            System.exit(1);
            return;
        }

        var repoDir = workDir.resolve("repo");
        // Stored in the main branch in the same folder name.
        var cacheDir = workDir.resolve("cache");
        var repoCacheDir = repoDir.resolve("cache");

        Properties props = new Properties();
        if (Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }
        try (Git git = GitTasks.setup(repoDir, gitRepo)) {
            if (Files.exists(repoCacheDir)) {
                Files.walkFileTree(repoCacheDir, new CopyingFileVisitor(repoCacheDir, cacheDir));
            }
            var dataProperties = cacheDir.resolve("versions.properties");
            if (Files.exists(dataProperties)) {
                try (Reader reader = Files.newBufferedReader(dataProperties)) {
                    props.load(reader);
                }
            }

            for (VersionManifest manifest : manifests) {
                LOGGER.info("Processing version {}", manifest.id());
                GitTasks.checkoutOrCreateBranch(git, branchNameForVersion(manifest));
                GitTasks.removeAllFiles(repoDir);

                CompletableFuture<Path> clientJar = manifest.requireDownloadAsync(http, versionsDir, "client", "jar");
                CompletableFuture<Path> clientMappings = manifest.requireDownloadAsync(http, versionsDir, "client_mappings", "mojmap");
                CompletableFuture.allOf(clientJar, clientMappings).join();

                Path remappedJar = clientJar.join().resolveSibling(FilenameUtils.getBaseName(clientJar.join().toString()) + "-remapped.jar");
                RemapperTasks.runRemapper(http, jdkProvider, toolsDir, clientJar.join(), remappedJar, clientMappings.join());

                List<LibraryTasks.LibraryDownload> libraries = LibraryTasks.getVersionLibraries(manifest, librariesDir);
                LibraryTasks.downloadLibraries(http, libraries);

                JavaVersion javaVersion = manifest.javaVersion() != null ? JavaVersion.parse(manifest.javaVersion().majorVersion() + "") : null;
                if (javaVersion == null) {
                    javaVersion = JavaVersion.JAVA_1_8;
                }

                DecompileTasks.decompileJar(
                        http,
                        jdkProvider,
                        toolsDir,
                        "0.0.10",
                        javaVersion,
                        FastStream.of(libraries)
                                .map(LibraryTasks.LibraryDownload::path)
                                .toList(),
                        remappedJar,
                        repoDir
                );
                ProjectTasks.generateProjectFiles(repoDir, javaVersion, libraries);
                GitTasks.stageAndCommit(git, "A commit!");
            }

            GitTasks.checkoutOrCreateBranch(git, "main");
            if (Files.exists(cacheDir)) {
                Files.walkFileTree(cacheDir, new CopyingFileVisitor(cacheDir, repoCacheDir));
            }
            ProjectTasks.emitReadme(repoDir); // TODO emit better readme.
            Files.writeString(repoDir.resolve(".gitignore"), """
                    # exclude all
                    /*
                    
                    # Include Important Folders
                    !cache/
                    
                    # Include git important files
                    !.gitignore
                    
                    # Other files.
                    !README.md
                    """
            );
            GitTasks.stageAndCommit(git, "A commit!");
            GitTasks.pushAllBranches(git);
        }
        LOGGER.info("Done!");
    }

    private static String branchNameForVersion(VersionManifest manifest) {
        return manifest.type() + "/" + manifest.id();
    }
}
