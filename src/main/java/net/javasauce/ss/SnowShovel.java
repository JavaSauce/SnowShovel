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
import net.javasauce.ss.tasks.report.GenerateReportTask;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.DeleteHierarchyVisitor;
import net.javasauce.ss.util.JdkProvider;
import net.javasauce.ss.util.VersionManifest;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

        OptionSpec<String> versionOpt = parser.acceptsAll(of("v", "version"), "Set the versions to process. Otherwise processes all.")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> gitRepoOpt = parser.acceptsAll(of("r", "repo"), "The git repository to use.")
                .withRequiredArg();

        OptionSpec<String> decompilerVersionOpt = parser.accepts("decompiler-version", "Set the decompiler version to use. Otherwise use the latest available version on Maven.")
                .withRequiredArg();

        OptionSpec<Void> gitPushOpt = parser.accepts("gitPush", "If SnowShovel should push to the repository.");
        OptionSpec<Void> gitCleanOpt = parser.accepts("gitClean", "If SnowShovel should delete the previous checkout (if available) before doing stuff.");

        OptionSpec<Boolean> snowShovelUpdatedOpt = parser.accepts("snowShovelUpdated", "If SnowShovel has been updated since last run. Disables updating versions and decompiler")
                .withRequiredArg()
                .ofType(Boolean.class);

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
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

        var ss = new SnowShovel(
                Path.of(".").toAbsolutePath().normalize(),
                optSet.has(gitPushOpt),
                optSet.has(gitCleanOpt)
        );
        ss.run(gitRepo, optSet.has(snowShovelUpdatedOpt), optSet.valuesOf(versionOpt), optSet.valueOf(decompilerVersionOpt));
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    public final Path workDir;
    public final boolean shouldPush;
    public final boolean shouldClean;

    public final Path versionsDir;
    public final Path librariesDir;
    public final Path toolsDir;

    public final Path repoDir;
    public final Path cacheDir;

    public final HttpEngine http;
    public final JdkProvider jdkProvider;

    public SnowShovel(Path workDir, boolean shouldPush, boolean shouldClean) {
        this.workDir = workDir;
        this.shouldPush = shouldPush;
        this.shouldClean = shouldClean;

        versionsDir = workDir.resolve("versions");
        librariesDir = workDir.resolve("libraries");
        toolsDir = workDir.resolve("tools");

        repoDir = workDir.resolve("repo");
        cacheDir = workDir.resolve("cache");
        http = new Curl4jHttpEngine(CABundle.builtIn());
        jdkProvider = new JdkProvider(toolsDir.resolve("jdks/"), http);
    }

    private void run(String gitRepo, boolean snowShovelUpdated, List<String> versionFilter, @Nullable String decompilerVersion) throws IOException {
        // Nuke the repo if we are told to start from scratch.
        if (shouldClean && Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }
        // Initialize the git repo, this will either clone, set up a local repo, or switch to the Main branch ready to work.
        try (Git git = GitTasks.setup(repoDir, gitRepo)) {
            // Pull the versions cache and properties from the main branch.
            Map<String, String> data = pullCache();

            // TODO load these initially from the cached existing file on each branch.
            //      We will need this for partial updates, so the main branch stats stay consistent.
            LinkedHashMap<String, TestCaseDef> testStats = new LinkedHashMap<>();

            // TODO in automatic mode we need to figure out what has changed. It will either be:
            //      - a new Minecraft Version
            //      - a Minecraft Version change
            //      - a SnowShovel update (FastRemapper or other change)
            //      - a Decompiler update

            // Find the versions we are targeting.
            List<VersionManifest> manifests = VersionManifestTasks.allVersionsWithMappings(this, versionFilter, !snowShovelUpdated);
            LOGGER.info("Identified {} versions with manifests.", manifests.size());
            for (VersionManifest manifest : manifests) {
                LOGGER.info("Processing version {}", manifest.id());
                // Checkout or create a branch for this version.
                // We then immediately delete all files except the .git folder, because we are lazy :)
                GitTasks.checkoutOrCreateBranch(git, branchNameForVersion(manifest));
                GitTasks.removeAllFiles(repoDir);

                // Download the client jar and client mappings proguard log.
                var clientJarFuture = manifest.requireDownloadAsync(http, versionsDir, "client", "jar");
                var clientMappingsFuture = manifest.requireDownloadAsync(http, versionsDir, "client_mappings", "mojmap");
                CompletableFuture.allOf(clientJarFuture, clientMappingsFuture).join();
                var clientJar = clientJarFuture.join();
                var clientMappings = clientMappingsFuture.join();

                // Remap the jar.
                var remappedJar = clientJar.resolveSibling(FilenameUtils.getBaseName(clientJar.toString()) + "-remapped.jar");
                RemapperTasks.runRemapper(this, clientJar, clientMappings, remappedJar);

                // Compute and download all the libraries.
                List<LibraryTasks.LibraryDownload> libraries = LibraryTasks.getVersionLibraries(manifest, librariesDir);
                LibraryTasks.downloadLibraries(http, libraries);

                // Run the decompiler.
                JavaVersion javaVersion = manifest.computeJavaVersion();
                DecompileTasks.decompileAndTest(
                        this,
                        decompilerVersion != null ? decompilerVersion : "0.0.14", // TODO use latest, or version in metadata.
                        javaVersion,
                        FastStream.of(libraries)
                                .map(LibraryTasks.LibraryDownload::path)
                                .toList(),
                        remappedJar,
                        repoDir
                );

                // Load the stats for this version.
                var stats = GenerateReportTask.loadTestStats(repoDir);
                if (stats != null) {
                    testStats.put(manifest.id(), stats);
                }
                // Generate Gradle project and misc files/reports, then commit the results.
                ProjectTasks.generateProjectFiles(this, javaVersion, libraries, manifest.id(), stats);
                GitTasks.stageAndCommit(git, "A commit!");
            }

            // Switch back to the main branch, push the cache, regenerate our .gitignore/readme and commit.
            GitTasks.checkoutOrCreateBranch(git, "main");
            pushCache(data);
            emitMainGitignore();
            emitMainReadme(testStats.reversed());

            GitTasks.stageAndCommit(git, "A commit!");

            // If enabled, push to git.
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

    private void emitMainReadme(Map<String, TestCaseDef> testStats) throws IOException {
        String readme = """
                # Shoveled
                Output of SnowShovel
                """;
        readme += GenerateReportTask.generateReport(testStats);

        Files.writeString(repoDir.resolve("README.md"), readme);
    }

    private static String branchNameForVersion(VersionManifest manifest) {
        return manifest.type() + "/" + manifest.id();
    }
}
