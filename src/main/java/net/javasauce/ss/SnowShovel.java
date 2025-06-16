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
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.*;
import net.javasauce.ss.tasks.report.DiscordReportTask;
import net.javasauce.ss.tasks.report.GenerateReportTask;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.*;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static java.util.List.of;

/**
 * Created by covers1624 on 1/19/25.
 */
public class SnowShovel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowShovel.class);

    private static final String VERSION;

    static {
        String version = null;
        var pkg = SnowShovel.class.getPackage();
        if (pkg != null) {
            version = pkg.getImplementationVersion();
        }

        VERSION = version != null ? version : "dev";
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(of("h", "help"), "Prints this help").forHelp();

        var autoOpt = parser.accepts("mode-auto", "Set SnowShovel into Auto mode. Determines what to run each time its invoked. Fist, handling updates of itself. Then, minecraft updates. Finally, decompiler updates.");
        var selfChangesOpt = parser.accepts("mode-snowShovelChanged", "Set SnowShovel into Self-change mode. Minecraft updates will not be polled and the Decompiler will not be updated. All versions will be re-processed.");
        var mcChangesOpt = parser.accepts("mode-minecraftChanged", "Set SnowShovel into Minecraft-change mode. Minecraft versions will be polled again, and updates will be processed. The Decompiler will not be updated. Only changed versions will be processed.");
        var decompilerChangesOpt = parser.accepts("mode-decompilerChanged", "Set SnowShovel into Decompiler-change mode. Minecraft updates will not be polled. The Decompiler will be updated. All versions will be re-processed.");

        // All these options are incompatible with each other.
        autoOpt.availableUnless(selfChangesOpt, mcChangesOpt, decompilerChangesOpt);
        selfChangesOpt.availableUnless(autoOpt, mcChangesOpt, decompilerChangesOpt);
        mcChangesOpt.availableUnless(autoOpt, selfChangesOpt, decompilerChangesOpt);
        decompilerChangesOpt.availableUnless(autoOpt, selfChangesOpt, mcChangesOpt);

        // One of them must be supplied.
        autoOpt.requiredUnless(selfChangesOpt, mcChangesOpt, decompilerChangesOpt);
        selfChangesOpt.requiredUnless(autoOpt, mcChangesOpt, decompilerChangesOpt);
        mcChangesOpt.requiredUnless(autoOpt, selfChangesOpt, decompilerChangesOpt);
        decompilerChangesOpt.requiredUnless(autoOpt, selfChangesOpt, mcChangesOpt);

        // Dev flags.
        OptionSpec<String> versionOpt = parser.accepts("only-version", "Dev only flag. Filter the versions to process, limited to any specified by this flag.")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> decompilerVersionOpt = parser.accepts("set-decompiler-version", "Dev only flag. Set the decompiler version to use.")
                .withRequiredArg();

        // Git flags.
        OptionSpec<String> gitRepoOpt = parser.accepts("gitRepo", "The remote git repository to use.")
                .withRequiredArg();

        OptionSpec<Void> gitPushOpt = parser.accepts("gitPush", "If SnowShovel should push to the repository.");
        OptionSpec<Void> gitCleanOpt = parser.accepts("gitClean", "If SnowShovel should delete the previous checkout (if available) before doing stuff.");

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

        Mode mode;
        if (optSet.has(autoOpt)) {
            mode = Mode.AUTOMATIC;
        } else if (optSet.has(selfChangesOpt)) {
            mode = Mode.SELF_CHANGES;
        } else if (optSet.has(mcChangesOpt)) {
            mode = Mode.MC_CHANGES;
        } else if (optSet.has(decompilerChangesOpt)) {
            mode = Mode.DECOMPILER_CHANGES;
        } else {
            throw new RuntimeException("No modes specified?");
        }

        var ss = new SnowShovel(
                Path.of(".").toAbsolutePath().normalize(),
                optSet.has(gitPushOpt),
                optSet.has(gitCleanOpt),
                optSet.valueOf(decompilerVersionOpt),
                optSet.valuesOf(versionOpt)
        );
        ss.run(gitRepo, mode);
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static final String TAG_SNOW_SHOVEL_VERSION = "SnowShovelVersion";
    private static final String TAG_DECOMPILER_VERSION = "DecompilerVersion";

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

    public final ToolProvider fastRemapper;
    public final ToolProvider decompiler;

    public final @Nullable String decompilerOverride;
    public final List<String> mcVersionsOverride;

    private final Map<String, String> data = new HashMap<>();
    private final Map<String, TestCaseDef> preTestStats = new HashMap<>();
    private final Map<String, TestCaseDef> testStats = new HashMap<>();

    private @Nullable String mainCommitTitle;

    public SnowShovel(Path workDir, boolean shouldPush, boolean shouldClean, @Nullable String decompilerOverride, List<String> mcVersionsOverride) {
        this.workDir = workDir;
        this.shouldPush = shouldPush;
        this.shouldClean = shouldClean;
        this.decompilerOverride = decompilerOverride;
        this.mcVersionsOverride = mcVersionsOverride;

        versionsDir = workDir.resolve("versions");
        librariesDir = workDir.resolve("libraries");
        toolsDir = workDir.resolve("tools");

        repoDir = workDir.resolve("repo");
        cacheDir = workDir.resolve("cache");
        http = new Curl4jHttpEngine(CABundle.builtIn());
        jdkProvider = new JdkProvider(toolsDir.resolve("jdks/"), http);

        fastRemapper = new ToolProvider(this, MavenNotation.parse("net.covers1624:FastRemapper:0:all"));
        decompiler = new ToolProvider(this, MavenNotation.parse("net.javasauce:Decompiler:0:testframework@zip"))
                .enableExtraction();
    }

    private void run(String gitRepo, Mode mode) throws IOException {
        // Nuke the repo if we are told to start from scratch.
        if (shouldClean && Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }
        // Initialize the git repo, this will either clone, set up a local repo, or switch to the Main branch ready to work.
        try (Git git = GitTasks.setup(repoDir, gitRepo)) {
            // Pull the versions cache and properties from the main branch.
            data.putAll(pullCache());
            // TODO load TestCaseDef's from all branches as default values.

            var branches = GitTasks.listAllBranches(git);
            for (var entry : branches) {
                String name = entry.name();
                if (!name.startsWith("release/") && !name.startsWith("snapshot/")) continue;

                String id = name.replace("release/", "").replace("snapshot/", "");
                GitTasks.loadBlob(git, entry.commit() + ":src/main/resources/test_stats.json", stream -> {
                    preTestStats.put(
                            id,
                            TestCaseDef.loadTestStats(stream)
                    );
                });
            }
            testStats.putAll(preTestStats);

            boolean didWork = switch (mode) {
                case AUTOMATIC -> tryRunAutomatic(git);
                case SELF_CHANGES -> tryRunSelfChanges(git);
                case MC_CHANGES -> tryRunMinecraftChanges(git);
                case DECOMPILER_CHANGES -> tryRunDecompilerChanges(git);
            };

            if (didWork) {
                // Switch back to the main branch, push the cache, regenerate our .gitignore/readme and commit.
                GitTasks.checkoutOrCreateBranch(git, "main");
                pushCache(data);
                emitMainGitignore();
                emitMainReadme();

                if (mainCommitTitle == null) {
                    throw new RuntimeException("No commit title was collected.");
                }
                GitTasks.stageAndCommit(git, mainCommitTitle);

                // If enabled, push to git.
                if (shouldPush) {
                    GitTasks.pushAllBranches(git);
                }

                String webhook = System.getenv("DISCORD_WEBHOOK");
                if (webhook != null) {
                    DiscordReportTask.generateReports(http, webhook, preTestStats, testStats);
                }
            } else {
                LOGGER.info("Nothing to do.");
            }
        }
        LOGGER.info("Done!");
    }

    @SuppressWarnings ("RedundantIfStatement")
    private boolean tryRunAutomatic(Git git) throws IOException {
        LOGGER.info("Running in Automatic mode.");
        mainCommitTitle = "Automatic run: ";
        if (tryRunSelfChanges(git)) return true;
        if (tryRunMinecraftChanges(git)) return true;
        if (tryRunDecompilerChanges(git)) return true;

        return false;
    }

    private boolean tryRunSelfChanges(Git git) throws IOException {
        LOGGER.info("Checking for changes to SnowShovel version since last run..");
        var prev = data.get(TAG_SNOW_SHOVEL_VERSION);
        if (VERSION.equals(prev)) return false;

        String commitTitle = "SnowShovel updated from " + prev + " to " + VERSION;
        mainCommitTitle += " " + commitTitle;

        LOGGER.info(commitTitle);
        processAllVersions(git, data.get(TAG_DECOMPILER_VERSION), (_, _) -> commitTitle);
        return true;
    }

    private boolean tryRunMinecraftChanges(Git git) throws IOException {
        LOGGER.info("Checking for changes to Minecraft versions since last run..");
        var changedVersions = VersionManifestTasks.changedVersions(this);
        if (changedVersions == null) return false;
        LOGGER.info(" The following versions changed: {}", FastStream.of(changedVersions).map(VersionListManifest.Version::id).toList());

        mainCommitTitle += " New Minecraft versions or changes.";

        processVersions(
                git,
                VersionManifestTasks.getManifests(this, changedVersions),
                data.get(TAG_DECOMPILER_VERSION),
                (n, e) -> {
                    if (n) {
                        return "New version " + e.id();
                    }
                    return "Version manifest changed.";
                }
        );
        return true;
    }

    private boolean tryRunDecompilerChanges(Git git) throws IOException {
        LOGGER.info("Checking for changes to Decompiler version since last run..");
        var prev = data.get(TAG_DECOMPILER_VERSION);
        String latestDecompiler = decompiler.findLatest();
        if (latestDecompiler.equals(prev)) return false;
        LOGGER.info(" Decompiler version changed from {} to {}", prev, latestDecompiler);

        String commitTitle = "Decompiler updated from " + prev + " to " + VERSION;
        mainCommitTitle += " " + commitTitle;

        processAllVersions(git, latestDecompiler, (_, _) -> commitTitle);
        return true;
    }

    private void processAllVersions(Git git, @Nullable String decompilerVersion, BiFunction<Boolean, VersionManifest, String> commitNameFunc) throws IOException {
        processVersions(
                git,
                VersionManifestTasks.getManifests(this, VersionManifestTasks.allVersions(this)),
                decompilerVersion,
                commitNameFunc
        );
    }

    private void processVersions(Git git, List<VersionManifest> manifests, @Nullable String decompilerVersion, BiFunction<Boolean, VersionManifest, String> commitNameFunc) throws IOException {
        // If we are overridden use that.
        if (decompilerOverride != null) decompilerVersion = decompilerOverride;

        if (decompilerVersion == null) {
            decompilerVersion = decompiler.findLatest();
        }
        fastRemapper.resolveWithVersion("0.3.2.18");
        decompiler.resolveWithVersion(decompilerVersion);
        var manifestsToProcess = filterTargets(manifests);
        LOGGER.info("Found {} versions to process.", manifestsToProcess.size());
        int i = 0;
        for (VersionManifest manifest : manifestsToProcess) {
            LOGGER.info("Processing version {} {}/{}", manifest.id(), ++i, manifestsToProcess.size());
            // Checkout or create a branch for this version.
            // We then immediately delete all files except the .git folder, because we are lazy :)
            boolean newBranch = GitTasks.checkoutOrCreateBranch(git, branchNameForVersion(manifest));
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
                    javaVersion,
                    FastStream.of(libraries)
                            .map(LibraryTasks.LibraryDownload::path)
                            .toList(),
                    remappedJar,
                    repoDir
            );

            // Load the stats for this version.
            TestCaseDef stats = null;
            Path statsFile = repoDir.resolve("src/main/resources/test_stats.json");
            if (Files.exists(statsFile)) {
                stats = TestCaseDef.loadTestStats(statsFile);
                testStats.put(manifest.id(), stats);
            }
            // Generate Gradle project and misc files/reports, then commit the results.
            ProjectTasks.generateProjectFiles(this, javaVersion, libraries, manifest.id(), stats);
            GitTasks.stageAndCommit(git, commitNameFunc.apply(newBranch, manifest));
        }

        data.put(TAG_SNOW_SHOVEL_VERSION, VERSION);
        data.put(TAG_DECOMPILER_VERSION, decompilerVersion);
    }

    private List<VersionManifest> filterTargets(List<VersionManifest> manifests) {
        if (mcVersionsOverride.isEmpty()) return manifests;

        return FastStream.of(manifests)
                .filter(e -> mcVersionsOverride.contains(e.id()))
                .toList();
    }

    private Map<String, String> pullCache() throws IOException {
        if (Files.exists(cacheDir)) {
            Files.walkFileTree(cacheDir, new DeleteHierarchyVisitor());
        }
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
        var dataJson = cacheDir.resolve("versions.json");
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

    private void emitMainReadme() throws IOException {
        String readme = """
                # Shoveled
                Output of SnowShovel
                """;
        readme += GenerateReportTask.generateReport(
                FastStream.of(VersionManifestTasks.allVersions(this).reversed())
                        .filter(e -> testStats.containsKey(e.id()))
                        .map(e -> new GenerateReportTask.ReportPair(e.id(), testStats.get(e.id())))
                        .toList()
        );

        Files.writeString(repoDir.resolve("README.md"), readme);
    }

    private static String branchNameForVersion(VersionManifest manifest) {
        return manifest.type() + "/" + manifest.id();
    }

    private enum Mode {
        AUTOMATIC,
        SELF_CHANGES,
        MC_CHANGES,
        DECOMPILER_CHANGES
    }
}
