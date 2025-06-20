package net.javasauce.ss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.EnumConverter;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.List.of;

/**
 * Created by covers1624 on 1/19/25.
 */
public class SnowShovel implements AutoCloseable {

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

        var modeOpt = parser.accepts("mode", "Set the mode to run in. Usually AUTO unless testing.")
                .withRequiredArg()
                .required()
                .withValuesConvertedBy(new EnumConverter<>(Mode.class) { });

        // Git flags.
        OptionSpec<String> gitRepoOpt = parser.accepts("gitRepo", "The remote git repository to use.")
                .withRequiredArg();

        OptionSpec<Void> gitPushOpt = parser.accepts("gitPush", "If SnowShovel should push to the repository.");
        OptionSpec<Void> gitCleanOpt = parser.accepts("gitClean", "If SnowShovel should delete the previous checkout (if available) before doing stuff.");

        // Dev flags.
        OptionSpec<String> versionOpt = parser.accepts("only-version", "Dev only flag. Filter the versions to process, limited to any specified by this flag.")
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> decompilerVersionOpt = parser.accepts("set-decompiler-version", "Dev only flag. Set the decompiler version to use.")
                .withRequiredArg();

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
                gitRepo,
                Path.of(".").toAbsolutePath().normalize(),
                optSet.has(gitPushOpt),
                optSet.has(gitCleanOpt),
                Optional.ofNullable(optSet.valueOf(decompilerVersionOpt)),
                optSet.valuesOf(versionOpt)
        );
        try (ss) {
            ss.run(optSet.valueOf(modeOpt));
        }

        LOGGER.info("Done!");
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static final String TAG_SNOW_SHOVEL_VERSION = "SnowShovelVersion";
    private static final String TAG_DECOMPILER_VERSION = "DecompilerVersion";

    public final String gitRepo;
    public final Path workDir;
    public final boolean shouldPush;
    public final boolean shouldClean;

    public final Optional<String> decompilerOverride;
    public final List<String> mcVersionsOverride;

    public final Path versionsDir;
    public final Path librariesDir;
    public final Path toolsDir;

    public final Path repoDir;
    public final Path cacheDir;

    public final HttpEngine http;
    public final JdkProvider jdkProvider;

    public final ToolProvider fastRemapper;
    public final ToolProvider decompiler;

    public final Git git;

    private final Map<String, String> data = new HashMap<>();
    private final Map<String, CommittedTestCaseDef> preTestStats = new HashMap<>();
    private final Map<String, CommittedTestCaseDef> testStats = new HashMap<>();
    private final Map<String, String> commitTitles = new HashMap<>();

    public SnowShovel(String gitRepo, Path workDir, boolean shouldPush, boolean shouldClean, Optional<String> decompilerOverride, List<String> mcVersionsOverride) throws IOException {
        this.gitRepo = gitRepo;
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

        // Nuke the repo if we are told to start from scratch.
        if (shouldClean && Files.exists(repoDir)) {
            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
        }

        git = GitTasks.setup(repoDir, gitRepo);

        pullCache();
    }

    private void run(Mode mode) throws IOException {
        pullStatsFromBranches();
        testStats.putAll(preTestStats);

        var runRequest = switch (mode) {
            case AUTO -> detectAutomaticChanges();
            case SELF_CHANGES -> detectSelfChanges();
            case MC_CHANGES -> detectMinecraftChanges();
            case DECOMPILER_CHANGES -> detectDecompilerChanges();
        };

        if (runRequest == null) {
            LOGGER.info("Nothing to do.");
            return;
        }

        // If we are overridden use that.
        var decompilerVersion = decompilerOverride
                .or(() -> Optional.ofNullable(runRequest.decompilerVersion))
                .orElseGet(decompiler::findLatest);

        Map<String, String> commitNames = FastStream.of(runRequest.versions)
                .toMap(e -> e.version.id(), e -> e.commitName);
        var manifests = VersionManifestTasks.getManifests(
                this,
                FastStream.of(runRequest.versions)
                        .map(e -> e.version)
                        .filter(e -> mcVersionsOverride.isEmpty() || mcVersionsOverride.contains(e.id()))
        );

        fastRemapper.resolveWithVersion("0.3.2.18");
        decompiler.resolveWithVersion(decompilerVersion);
        LOGGER.info("Found {} versions to process.", manifests.size());
        int i = 0;
        for (VersionManifest manifest : manifests) {
            LOGGER.info("Processing version {} {}/{}", manifest.id(), ++i, manifests.size());
            processVersion(git, manifest, commitNames.get(manifest.id()));
        }

        data.put(TAG_SNOW_SHOVEL_VERSION, VERSION);
        data.put(TAG_DECOMPILER_VERSION, decompilerVersion);

        commitAndPushToMain(runRequest);

        String webhook = System.getenv("DISCORD_WEBHOOK");
        if (webhook != null) {
            DiscordReportTask.generateReports(this, webhook, preTestStats, testStats, commitTitles);
        }
    }

    private void pullStatsFromBranches() throws IOException {
        var branches = GitTasks.listAllBranches(git);
        for (var entry : branches) {
            String name = entry.name();
            if (!name.startsWith("release/") && !name.startsWith("snapshot/")) continue;

            String id = name.replace("release/", "").replace("snapshot/", "");
            GitTasks.loadBlob(git, entry.commit() + ":src/main/resources/test_stats.json", stream -> {
                preTestStats.put(
                        id,
                        new CommittedTestCaseDef(entry.commit(), TestCaseDef.loadTestStats(stream))
                );
            });
        }
    }

    private @Nullable RunRequest detectAutomaticChanges() throws IOException {
        LOGGER.info("Running in Automatic mode.");
        RunRequest request = detectSelfChanges();
        if (request == null) {
            request = detectMinecraftChanges();
        }
        if (request == null) {
            request = detectDecompilerChanges();
        }

        if (request == null) return null;

        return new RunRequest(
                "Automatic run: " + request.reason,
                request.decompilerVersion,
                request.versions
        );
    }

    private @Nullable RunRequest detectSelfChanges() throws IOException {
        LOGGER.info("Checking for changes to SnowShovel version since last run..");
        var prev = data.get(TAG_SNOW_SHOVEL_VERSION);
        if (VERSION.equals(prev)) return null;

        var commit = "SnowShovel updated from " + prev + " to " + VERSION;
        return new RunRequest(
                commit,
                null,
                allVersions(commit)
        );
    }

    private @Nullable RunRequest detectMinecraftChanges() throws IOException {
        LOGGER.info("Checking for changes to Minecraft versions since last run..");
        var changedVersions = VersionManifestTasks.changedVersions(this);
        if (changedVersions == null) return null;
        LOGGER.info(" The following versions changed: {}", FastStream.of(changedVersions.versions()).map(VersionListManifest.Version::id).toList());

        return new RunRequest(
                "New Minecraft versions or changes.",
                null,
                FastStream.of(changedVersions.versions())
                        .map(e -> new VersionRequest(e, changedVersions.newVersions().contains(e) ? "New version: " + e.id() : "Version manifest changed."))
                        .toList()
        );
    }

    private @Nullable RunRequest detectDecompilerChanges() throws IOException {
        LOGGER.info("Checking for changes to Decompiler version since last run..");

        var prev = data.get(TAG_DECOMPILER_VERSION);
        String latestDecompiler = decompiler.findLatest();
        if (latestDecompiler.equals(prev)) return null;

        var commit = "Decompiler updated from " + prev + " to " + latestDecompiler;
        return new RunRequest(
                commit,
                latestDecompiler,
                allVersions(commit)
        );
    }

    private List<VersionRequest> allVersions(String commitName) throws IOException {
        return FastStream.of(VersionManifestTasks.allVersions(this))
                .map(e -> new VersionRequest(e, commitName))
                .toList();
    }

    private void processVersion(Git git, VersionManifest manifest, String commitName) throws IOException {
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
        }
        // Generate Gradle project and misc files/reports, then commit the results.
        ProjectTasks.generateProjectFiles(this, javaVersion, libraries, manifest.id(), stats);
        var commit = GitTasks.stageAndCommit(git, commitName);

        if (stats != null) {
            testStats.put(manifest.id(), new CommittedTestCaseDef(commit, stats));
        }
        commitTitles.put(manifest.id(), commitName);
    }

    private void pullCache() throws IOException {
        if (Files.exists(cacheDir)) Files.walkFileTree(cacheDir, new DeleteHierarchyVisitor());

        var repoCacheDir = repoDir.resolve("cache");
        if (Files.exists(repoCacheDir)) {
            Files.walkFileTree(repoCacheDir, new CopyingFileVisitor(repoCacheDir, cacheDir));
        }

        var dataJson = cacheDir.resolve("versions.json");
        if (Files.exists(dataJson)) {
            data.putAll(JsonUtils.parse(GSON, dataJson, MAP_STRING_TYPE, StandardCharsets.UTF_8));
        }
    }

    private void pushCache() throws IOException {
        var dataJson = cacheDir.resolve("versions.json");
        JsonUtils.write(GSON, dataJson, this.data, MAP_STRING_TYPE, StandardCharsets.UTF_8);

        var repoCacheDir = repoDir.resolve("cache");
        if (Files.exists(cacheDir)) {
            Files.walkFileTree(cacheDir, new CopyingFileVisitor(cacheDir, repoCacheDir));
        }
    }

    private void commitAndPushToMain(RunRequest runRequest) throws IOException {
        GitTasks.checkoutOrCreateBranch(git, "main");
        pushCache();
        emitMainGitignore();
        emitMainReadme();

        GitTasks.stageAndCommit(git, runRequest.reason);

        if (shouldPush) {
            GitTasks.pushAllBranches(git);
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
                        .map(e -> new GenerateReportTask.ReportPair(e.id(), testStats.get(e.id()).def()))
                        .toList()
        );

        Files.writeString(repoDir.resolve("README.md"), readme);
    }

    private static String branchNameForVersion(VersionManifest manifest) {
        return manifest.type() + "/" + manifest.id();
    }

    @Override
    public void close() {
        git.close();
    }

    private enum Mode {
        AUTO,
        SELF_CHANGES,
        MC_CHANGES,
        DECOMPILER_CHANGES
    }

    public record RunRequest(
            String reason,
            @Nullable String decompilerVersion,
            List<VersionRequest> versions
    ) { }

    public record VersionRequest(
            VersionListManifest.Version version,
            String commitName
    ) { }
}
