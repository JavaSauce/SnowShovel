package net.javasauce.ss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.covers1624.curl4j.CABundle;
import net.covers1624.curl4j.httpapi.Curl4jHttpEngine;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.CopyingFileVisitor;
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
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.List.of;

/**
 * Created by covers1624 on 1/19/25.
 */
public class SnowShovel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowShovel.class);

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(of("h", "help"), "Prints this help").forHelp();

        OptionSpec<String> versionOpt = parser.acceptsAll(of("v", "version"), "Process one or more minecraft versions.")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> gitRepoOpt = parser.acceptsAll(of("r", "repo"), "The git repository to use.")
                .withRequiredArg();

        OptionSpec<Void> gitPushOpt = parser.accepts("gitPush", "If SnowShovel should push to the repository.");
        OptionSpec<Void> gitCleanOpt = parser.accepts("gitClean", "If SnowShovel should delete the previous checkout (if available) before doing stuff.");

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
        var ss = new SnowShovel(
                Path.of(".").toAbsolutePath().normalize(),
                optSet.has(gitPushOpt),
                optSet.has(gitCleanOpt)
        );
        ss.run(gitRepo, versionIds);
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private final Path workDir;
    private final boolean shouldPush;
    private final boolean shouldClean;

    private final Path versionsDir;
    private final Path librariesDir;
    private final Path toolsDir;

    private final Path repoDir;
    private final Path cacheDir;

    public SnowShovel(Path workDir, boolean shouldPush, boolean shouldClean) {
        this.workDir = workDir;
        this.shouldPush = shouldPush;
        this.shouldClean = shouldClean;

        versionsDir = workDir.resolve("versions");
        librariesDir = workDir.resolve("libraries");
        toolsDir = workDir.resolve("tools");

        repoDir = workDir.resolve("repo");
        cacheDir = workDir.resolve("cache");
    }

    private void run(String gitRepo, List<String> versionIds) throws IOException {
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

        if (shouldClean && Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }
        try (Git git = GitTasks.setup(repoDir, gitRepo)) {
            Map<String, String> data = pullCache();

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
            pushCache(data);
            emitMainGitignore();
            emitMainReadme();

            GitTasks.stageAndCommit(git, "A commit!");
            if (shouldPush) {
                GitTasks.pushAllBranches(git);
            }
        }
        LOGGER.info("Done!");
    }

    private Map<String, String> pullCache() throws IOException {
        var repoCacheDir = repoDir.resolve("cache");
        if (Files.exists(repoCacheDir)) {
            Files.walkFileTree(repoCacheDir, new CopyingFileVisitor(repoCacheDir, cacheDir));
        }
        Map<String, String> data = null;
        var dataJson = cacheDir.resolve("versions.json");
        if (Files.exists(dataJson)) {
            data = JsonUtils.parse(GSON, dataJson, MAP_STRING_TYPE, StandardCharsets.UTF_8);
        }
        if (data == null) {
            data = new HashMap<>();
        }
        return data;
    }

    private void pushCache(Map<String, String> data) throws IOException {
        var dataJson = cacheDir.resolve("versions.properties");
        JsonUtils.write(GSON, dataJson, data, MAP_STRING_TYPE, StandardCharsets.UTF_8);

        var repoCacheDir = repoDir.resolve("cache");
        if (Files.exists(cacheDir)) {
            Files.walkFileTree(cacheDir, new CopyingFileVisitor(cacheDir, repoCacheDir));
        }
    }

    private void emitMainGitignore() throws IOException {
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
    }

    // TODO better readme for main branch.
    private void emitMainReadme() throws IOException {
        Files.writeString(repoDir.resolve("README.md"), """
                # Shoveled
                Output of SnowShovel
                """
        );
    }

    private static String branchNameForVersion(VersionManifest manifest) {
        return manifest.type() + "/" + manifest.id();
    }
}
