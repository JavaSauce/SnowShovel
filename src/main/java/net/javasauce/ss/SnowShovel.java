package net.javasauce.ss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.covers1624.curl4j.CABundle;
import net.covers1624.curl4j.httpapi.Curl4jHttpEngine;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.*;
import net.javasauce.ss.tasks.detect.DetectChangesTask;
import net.javasauce.ss.tasks.git.*;
import net.javasauce.ss.tasks.report.DiscordReportTask;
import net.javasauce.ss.tasks.report.GenerateComparisonsTask;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.tasks.util.*;
import net.javasauce.ss.util.*;
import net.javasauce.ss.util.task.Task;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

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

        var runMatrixBuilder = parser.accepts("run-matrix", "Run using a matrix segment.");
        var finalizeMatrixBuilder = parser.accepts("finalize-matrix", "Finalize a matrix run.");

        var genMatrixOpt = parser.accepts("gen-matrix", "Generate a matrix for the current work.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        var matrixSizeOpt = parser.accepts("matrix-size", "The number of versions to process in each job.")
                .availableIf(genMatrixOpt)
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(8);

        var useMatrixOpt = runMatrixBuilder
                .availableUnless(finalizeMatrixBuilder)
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));

        var finalizeMatrixOpt = finalizeMatrixBuilder
                .availableUnless(runMatrixBuilder)
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));

        // Git flags.
        OptionSpec<String> gitRepoOpt = parser.accepts("gitRepo", "The remote git repository to use.")
                .withRequiredArg();

        OptionSpec<Void> gitPushOpt = parser.accepts("gitPush", "If SnowShovel should push to the repository.");
        OptionSpec<Void> gitCleanOpt = parser.accepts("gitClean", "If SnowShovel should delete the previous checkout (if available) before doing stuff.");

        // Dev flags.
        OptionSpec<Void> simulateFullRunOpt = parser.accepts("simulate-full-run", "Manually run a full decompile of all versions.");

        OptionSpec<String> versionOpt = parser.accepts("only-version", "Dev only flag. Filter the versions to process, limited to any specified by this flag.")
                .availableIf(simulateFullRunOpt)
                .withRequiredArg()
                .withValuesSeparatedBy(",");

        OptionSpec<String> decompilerVersionOpt = parser.accepts("set-decompiler-version", "Dev only flag. Set the decompiler version to use.")
                .availableIf(simulateFullRunOpt)
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
            ss.doRun(optSet.has(simulateFullRunOpt));
//            RunRequest runRequest;
//            if (optSet.has(simulateFullRunOpt)) {
//                runRequest = ss.manualAllVersionsRun();
//            } else {
//                runRequest = ss.detectAutomaticChanges();
//            }
//            if (runRequest == null) {
//                LOGGER.info("Nothing to do.");
//                return;
//            }
//
//            if (optSet.has(genMatrixOpt)) {
//                ss.runGenMatrix(
//                        runRequest,
//                        optSet.valueOf(matrixSizeOpt),
//                        optSet.valueOf(genMatrixOpt)
//                );
//            } else if (optSet.has(useMatrixOpt)) {
//                ss.runUseMatrix(optSet.valueOf(useMatrixOpt));
//            } else if (optSet.has(finalizeMatrixOpt)) {
//                ss.runFinalizeMatrix(optSet.valueOf(finalizeMatrixOpt));
//            } else {
//                ss.run2(runRequest);
//            }
        }

        LOGGER.info("Done!");
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Gson GSON_MINIFIED = new GsonBuilder().create();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static final @Nullable String DISCORD_WEBHOOK = System.getenv("DISCORD_WEBHOOK");

    private static final String TAG_SNOW_SHOVEL_VERSION = "SnowShovelVersion";
    private static final String TAG_DECOMPILER_VERSION = "DecompilerVersion";

    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            new BasicThreadFactory.Builder()
                    .namingPattern("Download executor %d")
                    .daemon(true)
                    .build()
    );

    // Single thread executor to bottleneck the Remapper tasks through,
    // This is mostly for log clarity, so its logs aren't intertwined with others.
    private final ExecutorService remapperExecutor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Remapper Thread")
            .daemon(true)
            .build()
    );

    // Single thread executor to bottleneck the Decompile tasks through,
    // as the decompiler does its own threading in its spawned process
    private final ExecutorService decompileExecutor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Decompiler Thread")
            .daemon(true)
            .build()
    );

    // Single thread executor to ensure all git operations happen sequentially.
    private final ExecutorService gitExecutor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Git Thread")
            .daemon(true)
            .build()
    );

    public final String gitRepo;
    public final Path workDir;
    public final boolean shouldPush;
    public final boolean shouldClean;

    public final Optional<String> decompilerOverride;
    public final List<String> mcVersionsOverride;

    public final Path versionsDir;
    public final Path librariesDir;
    public final Path toolsDir;
    public final Path tempDir;

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

    private final Map<JavaVersion, SetupJdkTask> jdkTasks = new HashMap<>();

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
        tempDir = workDir.resolve("temp");

        repoDir = workDir.resolve("repo");
        cacheDir = workDir.resolve("cache");
        http = new Curl4jHttpEngine(CABundle.builtIn());
        jdkProvider = new JdkProvider(toolsDir.resolve("jdks/"), http);

        fastRemapper = new ToolProvider(this, MavenNotation.parse("net.covers1624:FastRemapper:0:all"));
        decompiler = new ToolProvider(this, MavenNotation.parse("net.javasauce:Decompiler:0:testframework@zip"))
                .enableExtraction();

        if (Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new DeleteHierarchyVisitor());
        }

//        // Nuke the repo if we are told to start from scratch.
//        if (shouldClean && Files.exists(repoDir)) {
//            Files.walkFileTree(repoDir, new DeleteHierarchyVisitor());
//        }
//
//        git = GitTasks.setup(repoDir, gitRepo);
        git = null;
    }

    private void doRun(boolean simulateFullRun) throws IOException {
        // Stage 0, Setup Git
        var gitSetupTask = SetupGitRepoTask.create("setupGit", gitExecutor, task -> {
            task.repoDir.set(repoDir);
            task.repoUrl.set(gitRepo);
            task.clearClone.set(shouldClean);
        });

        var checkoutMain = CheckoutBranchTask.create("checkoutMain", gitExecutor, task -> {
            task.git.set(gitSetupTask.output);
            task.branch.set("main");
        });

        // Stage 1, Detect changes.
        var detectChanges = DetectChangesTask.create("detectChanges", ForkJoinPool.commonPool(), task -> {
            task.dependsOn(checkoutMain);
            task.http.set(http);
            task.cacheDir.set(repoDir.resolve("cache"));
            task.versionFilters.set(mcVersionsOverride);
            task.decompilerOverride.set(decompilerOverride);
            task.simulateFullRun.set(simulateFullRun);
        });

        Task.runTasks(List.of(detectChanges));

        // Stage 1.5, Check if redundant, tag main with cache.
        var runRequest = detectChanges.runRequest.get().orElse(null);
        if (runRequest == null) return;

        var versionSet = detectChanges.versionSet.get();

        var tempTagMain = CommitTask.create("tagMain", gitExecutor, task -> {
            task.git.set(gitSetupTask.output);
            task.commitMessage.set(Optional.of(runRequest.reason()));
            task.tagName.set(Optional.of("temp/main"));
        });
        Task.runTasks(List.of(tempTagMain));

        // TODO split here, gen matrix ends here, use matrix starts.

        // Stage 2
        var prepareRemapper = PrepareToolTask.create("prepareRemapper", downloadExecutor, http, task -> {
            task.notation.set(MavenNotation.parse("net.covers1624:FastRemapper:0.3.2.20@zip"));
            task.toolDir.set(toolsDir);
        });

        var prepareDecompiler = PrepareToolTask.create("prepareDecompiler", downloadExecutor, http, task -> {
            task.notation.set(MavenNotation.parse("net.javasauce:Decompiler:0:testframework@zip").withVersion(runRequest.decompilerVersion()));
            task.toolDir.set(toolsDir);
        });

        var downloadGradleWrapper = NewDownloadTask.create("downloadGradleWrapper", downloadExecutor, http, task -> {
            task.output.set(librariesDir.resolve("GradleWrapper.zip"));
            task.url.set("https://covers1624.net/Files/GradleWrapper-8.10.2.zip");
            task.downloadLen.set(44825L);
            task.downloadHash.set(Optional.of("2e355d2ede2307bfe40330db29f52b9b729fd9b2"));
        });

        Map<LibraryTasks.LibraryDownload, NewDownloadTask> libraryDownloads = new HashMap<>();

        var gitTagAllBarrier = new BarrierTask("gitTagAllBarrier");
        for (var version : runRequest.versions()) {
            var id = version.id();
            var manifest = versionSet.getManifest(id);

            var downloadClient = NewDownloadTask.create("downloadClient_" + id, downloadExecutor, http, task -> {
                var download = manifest.downloads().get("client");
                task.output.set(versionsDir.resolve(id).resolve(id + "-client.jar"));
                task.url.set(download.url());
                task.downloadHash.set(Optional.of(download.sha1()));
                task.downloadLen.set(download.size());
            });

            var downloadClientMappings = NewDownloadTask.create("downloadClientMappings_" + id, downloadExecutor, http, task -> {
                var download = manifest.downloads().get("client_mappings");
                task.output.set(versionsDir.resolve(id).resolve(id + "-client_mappings.jar"));
                task.url.set(download.url());
                task.downloadHash.set(Optional.of(download.sha1()));
                task.downloadLen.set(download.size());
            });

            var remapClient = RemapperTask.create("remapClient_" + id, remapperExecutor, task -> {
                task.tool.set(prepareRemapper.output);
                task.javaHome.set(getJdkTask(JavaVersion.JAVA_17).javaHome);
                task.input.set(downloadClient.output);
                task.mappings.set(downloadClientMappings.output);
                task.remapped.set(versionsDir.resolve(id).resolve(id + "-client-remapped.jar"));
            });

            var libDefs = LibraryTasks.getVersionLibraries(manifest, librariesDir);
            List<NewDownloadTask> libraries = FastStream.of(libDefs)
                    .map(library -> libraryDownloads.computeIfAbsent(library, e2 ->
                            NewDownloadTask.create("downloadLibrary_" + library.notation(), downloadExecutor, http, task -> {
                                task.url.set(library.url());
                                task.output.set(library.path());
                                task.downloadHash.set(Optional.ofNullable(library.sha1()));
                                task.downloadLen.set(library.size());
                            })))
                    .toList();

            var decompileTask = DecompileTask.create("decompile_" + id, decompileExecutor, task -> {
                task.javaHome.set(getJdkTask(manifest.computeJavaVersion()).javaHome);
                task.tool.set(prepareDecompiler.output);
                task.libraries.set(FastStream.of(libraries).map(e -> e.output).toList());
                task.inputJar.set(remapClient.remapped);
                task.output.set(tempDir.resolve(id));
            });

            var branchName = manifest.computeBranchName();
            var checkoutBranchTask = CheckoutBranchTask.create("checkout_" + id, gitExecutor, task -> {
                task.dependsOn(decompileTask);
                task.git.set(gitSetupTask.output);
                task.branch.set(branchName);
                task.clean.set(true);
            });

            var copyTask = CopyTask.create("copyDecompileResults_" + id, gitExecutor, task -> {
                task.dependsOn(checkoutBranchTask);
                task.input.set(decompileTask.output);
                task.output.set(repoDir);
            });

            // TODO we can run this in parallel with copy, but not due to the executors they use.
            //      We can probably also move away from using a gradle wrapper dist zip now, and just run gradle to gen a wrapper
            //      we only ever used the dist zip because it was faster than stalling the program waiting for Gradle.
            var genProjectTask = GenerateGradleProjectTask.create("generateGradleProject_" + id, gitExecutor, task -> {
                task.dependsOn(checkoutBranchTask);
                task.projectDir.set(repoDir);
                task.gradleWrapperDist.set(downloadGradleWrapper.output);
                task.javaVersion.set(manifest.computeJavaVersion());
                task.libraries.set(libDefs);
                task.mcVersion.set(id);
            });

            var commitTask = CommitTask.create("commitAndTag_" + id, gitExecutor, task -> {
                task.dependsOn(copyTask);
                task.dependsOn(genProjectTask);
                task.git.set(gitSetupTask.output);
                task.commitMessage.set(Optional.of(version.commitName()));
                task.tagName.set(Optional.of("temp/" + branchName));
            });
            gitTagAllBarrier.dependsOn(commitTask);
        }

        var pushAllTagsBarrier = new BarrierTask("pushAllTags");
        pushAllTagsBarrier.dependsOn(gitTagAllBarrier);
        if (shouldPush) {
            var pushTask = PushAllTask.create("pushAllTags", gitExecutor, task -> {
                task.tags.set(true);
            });
            pushAllTagsBarrier.dependsOn(pushTask);
        }

        // TODO split here, Use matrix ends here, finalize matrix begins.

        // Stage 3
        var preStats = ExtractTestStatsTask.create("preFFExtractTestStats", gitExecutor, task -> {
            task.dependsOn(pushAllTagsBarrier);
            task.git.set(gitSetupTask.output);
            task.checkOrigin.set(true);
            task.versionSet.set(versionSet);
        });

        var fastForwardBarrier = new BarrierTask("fastForwardBarrier");
        for (var version : runRequest.versions()) {
            var manifest = versionSet.getManifest(version.id());
            var branch = manifest.computeBranchName();
            var fastForward = FastForwardTask.create("fastForward_" + version.id(), gitExecutor, task -> {
                task.dependsOn(preStats);
                task.git.set(gitSetupTask.output);
                task.branch.set(branch);
                task.tag.set(Optional.of("temp/" + branch));
            });
            fastForwardBarrier.dependsOn(fastForward);
        }

        var fastForwardMain = FastForwardTask.create("fastForwardMain", gitExecutor, task -> {
            task.dependsOn(fastForwardBarrier);
            task.git.set(gitSetupTask.output);
            task.branch.set("main");
            task.tag.set(Optional.of("temp/main"));
        });

        var postStats = ExtractTestStatsTask.create("postFFExtractTestStats", gitExecutor, task -> {
            task.dependsOn(fastForwardMain);
            task.git.set(gitSetupTask.output);
            task.checkOrigin.set(false);
            task.versionSet.set(versionSet);
        });

        var genRootProject = GenerateRootProjectTask.create("genRootProject", ForkJoinPool.commonPool(), task -> {
            task.dependsOn(fastForwardMain);
            task.projectDir.set(repoDir);
            task.versions.set(versionSet.allVersions());
            task.testDefs.set(postStats.testStats);
        });

        var amendMain = CommitTask.create("amendMain", gitExecutor, task -> {
            task.dependsOn(genRootProject);
            task.git.set(gitSetupTask.output);
            task.amend.set(true);
        });

        var pushBarrier = new BarrierTask("pushBarrier");
        pushBarrier.dependsOn(amendMain);

        if (shouldPush) {
            var pushTask = PushAllTask.create("pushAllBranches", gitExecutor, task -> {
                task.dependsOn(amendMain);
                task.tags.set(false);
            });
            pushBarrier.dependsOn(pushTask);
        }

        var discordPostBarrier = new BarrierTask("discordPostBarrier");
        if (DISCORD_WEBHOOK != null) {
            var genComparisons = GenerateComparisonsTask.create("genComparisons", ForkJoinPool.commonPool(), task -> {
                task.dependsOn(pushBarrier);
                task.preStats.set(preStats.testStats);
                task.postStats.set(postStats.testStats);
            });

            var discordReport = DiscordReportTask.create("discordReport", ForkJoinPool.commonPool(), task -> {
                task.webhook.set(DISCORD_WEBHOOK);
                task.gitRepoUrl.set(gitRepo);
                task.http.set(http);
                task.versions.set(versionSet.allVersions());
                task.postDefs.set(postStats.testStats);
                task.comparisons.set(genComparisons.comparisons);
            });
            discordPostBarrier.dependsOn(discordReport);
        }

        Task.runTasks(List.of(pushBarrier, discordPostBarrier));
    }

    private SetupJdkTask getJdkTask(JavaVersion javaVersion) {
        return jdkTasks.computeIfAbsent(javaVersion, e ->
                SetupJdkTask.create("provisionJdk_" + javaVersion.shortString, downloadExecutor, jdkProvider, task -> {
                    task.javaVersion.set(javaVersion);
                })
        );
    }

    private void run(RunRequest runRequest) throws IOException {
        preTestStats.putAll(pullStatsFromBranches());
        testStats.putAll(preTestStats);

        var toProcess = prepareRequestedVersions(runRequest.versions)
                .toList();
        var decompilerVersion = decompilerOverride
                .or(() -> Optional.ofNullable(runRequest.decompilerVersion))
                .orElseGet(decompiler::findLatest);
        fastRemapper.resolveWithVersion("0.3.2.18");
        decompiler.resolveWithVersion(decompilerVersion);
        LOGGER.info("Found {} versions to process.", toProcess.size());
        int i = 0;
        for (var version : toProcess) {
            var manifest = version.manifest;
            LOGGER.info("Processing version {} {}/{}", manifest.id(), ++i, toProcess.size());
            GitTasks.checkoutOrCreateBranch(git, manifest.computeBranchName());
            GitTasks.removeAllFiles(repoDir);

            var stats = processVersion(manifest);

            var commit = GitTasks.stageAndCommit(git, version.commitName);

            if (stats != null) {
                testStats.put(manifest.id(), new CommittedTestCaseDef(commit, version.commitName, stats));
            }
            commitTitles.put(manifest.id(), version.commitName);
        }

        data.put(TAG_SNOW_SHOVEL_VERSION, VERSION);
        data.put(TAG_DECOMPILER_VERSION, decompilerVersion);

        GitTasks.checkoutOrCreateBranch(git, "main");
        rebuildMain();
        GitTasks.stageAndCommit(git, runRequest.reason);

        if (shouldPush) {
            GitTasks.pushAllBranches(git);
        }

        if (DISCORD_WEBHOOK != null) {
            DiscordReportTask.generateReports(this, DISCORD_WEBHOOK, preTestStats, testStats, commitTitles, toProcess.size(), runRequest.reason);
        }
    }

    private void runGenMatrix(RunRequest runRequest, int size, Path matrixOutput) throws IOException {
        var decompilerVersion = decompilerOverride
                .or(() -> Optional.ofNullable(runRequest.decompilerVersion))
                .orElseGet(decompiler::findLatest);

        var prepared = prepareRequestedVersions(runRequest.versions)
                .toList();
        LOGGER.info("{} versions to process.", prepared.size());
        JobMatrix matrix = new JobMatrix(FastStream.of(prepared)
                .partition(size)
                .map(e -> new MatrixJob(
                        "job",
                        new MatrixJobConfig(e
                                .map(e2 -> new MatrixJobConfig.JobVersion(e2.manifest.id(), e2.commitName()))
                                .toList(),
                                decompilerVersion
                        )
                ))
                .toList()
        );

        LOGGER.info("Writing job matrix.");
        JsonUtils.write(GSON_MINIFIED, matrixOutput, matrix);

        // Only push cache here.
        GitTasks.checkoutOrCreateBranch(git, "main");
        pushCache();
        GitTasks.stageAndCommit(git, runRequest.reason);
        GitTasks.createTag(git, "temp/main");

        if (shouldPush) {
            GitTasks.pushAllTags(git);
        }
    }

    private void runUseMatrix(Path matrixInput) throws IOException {
        MatrixJobConfig config = JsonUtils.parse(GSON, matrixInput, MatrixJobConfig.class);

        fastForwardMainToTempTag();
        pullCache();

        var toProcess = prepareRequestedMatrixVersions(FastStream.of(config.versions))
                .toList();
        fastRemapper.resolveWithVersion("0.3.2.18");
        decompiler.resolveWithVersion(config.decompilerVersion);
        LOGGER.info("Found {} versions to process.", toProcess.size());
        int i = 0;
        for (var version : toProcess) {
            var manifest = version.manifest;
            LOGGER.info("Processing version {} {}/{}", manifest.id(), ++i, toProcess.size());
            GitTasks.checkoutOrCreateBranch(git, manifest.computeBranchName());
            GitTasks.removeAllFiles(repoDir);

            processVersion(manifest);

            GitTasks.stageAndCommit(git, version.commitName);
            GitTasks.createTag(git, "temp/" + manifest.computeBranchName());
        }
        if (shouldPush) {
            GitTasks.pushAllTags(git);
        }
    }

    private void runFinalizeMatrix(Path matrixInput) throws IOException {
        JobMatrix config = JsonUtils.parse(GSON, matrixInput, JobMatrix.class);

        fastForwardMainToTempTag();
        pullCache();

        preTestStats.putAll(pullStatsFromBranches(true));

        List<String> tagsToDelete = new ArrayList<>();
        tagsToDelete.add("temp/main");
        var toProcess = prepareRequestedMatrixVersions(FastStream.of(config.jobs).flatMap(e -> e.parseConfig().versions))
                .toList();
        var allTags = FastStream.of(GitTasks.listAllTags(git))
                .toMap(GitTasks.TagEntry::name, GitTasks.TagEntry::commit);
        for (var version : toProcess) {
            var branchName = version.manifest.computeBranchName();
            var tagName = "temp/" + branchName;
            var tagCommit = allTags.get(tagName);
            if (tagCommit == null) {
                throw new RuntimeException("Tag " + tagName + " did not get created and pushed.");
            }
            GitTasks.fastForwardBranchToCommit(git, branchName, tagCommit);
            commitTitles.put(version.manifest.id(), version.commitName);
            tagsToDelete.add(tagName);
        }

        testStats.putAll(pullStatsFromBranches());

        data.put(TAG_SNOW_SHOVEL_VERSION, VERSION);
        data.put(TAG_DECOMPILER_VERSION, config.jobs.getFirst().parseConfig().decompilerVersion);

        GitTasks.checkoutOrCreateBranch(git, "main");
        rebuildMain();
        GitTasks.stageAndAmend(git);

        GitTasks.deleteTags(git, tagsToDelete);

        if (shouldPush) {
            // TODO we should also perhaps do a git gc. I imagine the ref reuse will be quite poor.
            GitTasks.pushAllBranches(git);
            GitTasks.pushDeleteTags(git, tagsToDelete);
        }

        if (DISCORD_WEBHOOK != null) {
            DiscordReportTask.generateReports(
                    this,
                    DISCORD_WEBHOOK,
                    preTestStats,
                    testStats,
                    commitTitles,
                    toProcess.size(),
                    GitTasks.getCommitMessage(git, Constants.HEAD)
            );
        }
    }

    private void fastForwardMainToTempTag() {
        var tag = FastStream.of(GitTasks.listAllTags(git))
                .filter(e -> e.name().equals("temp/main"))
                .onlyOrDefault();
        if (tag == null) throw new RuntimeException("No temp/main tag");

        GitTasks.fastForwardBranchToCommit(git, "main", tag.commit());
    }

    private Map<String, CommittedTestCaseDef> pullStatsFromBranches() throws IOException {
        return pullStatsFromBranches(false);
    }

    private Map<String, CommittedTestCaseDef> pullStatsFromBranches(boolean tryOrigin) throws IOException {
        var branches = GitTasks.listAllBranches(git);

        Map<String, String> idToCommit = new HashMap<>();
        if (tryOrigin) {
            FastStream.of(branches)
                    .filter(e -> e.name().startsWith("origin/"))
                    .forEach(e -> parseBranchToId(e.name().replace("origin/", ""), e.commit(), idToCommit));
        }
        FastStream.of(branches)
                .forEach(e -> parseBranchToId(e.name(), e.commit(), idToCommit));
        Map<String, CommittedTestCaseDef> defs = new HashMap<>();
        for (var entry : idToCommit.entrySet()) {
            GitTasks.loadBlob(git, entry.getValue() + ":src/main/resources/test_stats.json", stream -> {
                defs.put(
                        entry.getKey(),
                        new CommittedTestCaseDef(entry.getValue(), GitTasks.getCommitMessage(git, entry.getValue()), TestCaseDef.loadTestStats(stream))
                );
            });
        }
        return defs;
    }

    private static void parseBranchToId(String branch, String commit, Map<String, String> idToCommit) {
        if (branch.startsWith("release/") || branch.startsWith("snapshot/")) {
            idToCommit.put(branch.replace("release/", "").replace("snapshot/", ""), commit);
        }
    }

    private RunRequest manualAllVersionsRun() throws IOException {
        LOGGER.info("Running in Manual mode. Re-processing everything.");
        return new RunRequest(
                "Manual run: Process all versions.",
                null,
                allVersions("Manual run: Process all versions.")
        );
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

    private FastStream<ProcessableVersion> prepareRequestedVersions(List<VersionRequest> requests) {
        Map<String, String> commitNames = FastStream.of(requests)
                .toMap(e -> e.version.id(), e -> e.commitName);
        var manifestsStream = VersionManifestTasks.getManifests(
                this,
                FastStream.of(requests)
                        .map(e -> e.version)
                        .filter(e -> mcVersionsOverride.isEmpty() || mcVersionsOverride.contains(e.id()))
        );
        return manifestsStream
                .map(e -> new ProcessableVersion(e, commitNames.get(e.id())));

    }

    private FastStream<ProcessableVersion> prepareRequestedMatrixVersions(FastStream<MatrixJobConfig.JobVersion> jobVersions) throws IOException {
        Map<String, String> idToCommit = jobVersions
                .toMap(MatrixJobConfig.JobVersion::id, e -> e.commitName);

        var manifestsStream = VersionManifestTasks.getManifests(
                this,
                FastStream.of(VersionManifestTasks.allVersions(this))
                        .filter(e -> idToCommit.containsKey(e.id()))
        );
        return manifestsStream
                .map(e -> new ProcessableVersion(e, idToCommit.get(e.id())));
    }

    private @Nullable TestCaseDef processVersion(VersionManifest manifest) throws IOException {
        // Download the client jar and client mappings proguard log.
        var clientJarFuture = manifest.requireDownloadAsync(http, versionsDir, "client", "jar");
        var clientMappingsFuture = manifest.requireDownloadAsync(http, versionsDir, "client_mappings", "mojmap");
        CompletableFuture.allOf(clientJarFuture, clientMappingsFuture).join();
        var clientJar = clientJarFuture.join();
        var clientMappings = clientMappingsFuture.join();

        // Remap the jar.
        var remappedJar = clientJar.resolveSibling(FilenameUtils.getBaseName(clientJar.toString()) + "-remapped.jar");
        RemapperTask.runRemapper(this, clientJar, clientMappings, remappedJar);

        // Compute and download all the libraries.
        List<LibraryTasks.LibraryDownload> libraries = LibraryTasks.getVersionLibraries(manifest, librariesDir);
        LibraryTasks.downloadLibraries(http, libraries);

        // Run the decompiler.
        JavaVersion javaVersion = manifest.computeJavaVersion();
        DecompileTask.decompileAndTest(
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
        return stats;
    }

    private void pullCache() throws IOException {
        LOGGER.info("Pulling caches from checked out repo.");
        if (Files.exists(cacheDir)) Files.walkFileTree(cacheDir, new DeleteHierarchyVisitor());

        var repoCacheDir = repoDir.resolve("cache");
        if (Files.exists(repoCacheDir)) {
            LOGGER.info("Pulling cache from repo.");
            Files.walkFileTree(repoCacheDir, new CopyingFileVisitor(repoCacheDir, cacheDir));
        }

        var dataJson = cacheDir.resolve("versions.json");
        if (Files.exists(dataJson)) {
            LOGGER.info("Reading data json cache from {}", dataJson);
            data.putAll(JsonUtils.parse(GSON, dataJson, MAP_STRING_TYPE, StandardCharsets.UTF_8));
            LOGGER.info("Got {}", data);
        }
    }

    private void rebuildMain() throws IOException {
        pushCache();
        emitMainGitignore();
        emitMainReadme();
    }

    private void pushCache() throws IOException {
        var dataJson = cacheDir.resolve("versions.json");
        JsonUtils.write(GSON, dataJson, this.data, MAP_STRING_TYPE, StandardCharsets.UTF_8);

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
        readme += GenerateComparisonsTask.generateReport(
                FastStream.of(VersionManifestTasks.allVersions(this).reversed())
                        .filter(e -> testStats.containsKey(e.id()))
                        .map(e -> new GenerateComparisonsTask.ReportPair(e.id(), testStats.get(e.id()).def()))
                        .toList()
        );

        Files.writeString(repoDir.resolve("README.md"), readme);
    }

    @Override
    public void close() {
        git.close();
        downloadExecutor.close();
        remapperExecutor.close();
        decompileExecutor.close();
        gitExecutor.close();
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

    public record ProcessableVersion(
            VersionManifest manifest,
            String commitName
    ) { }

    public record JobMatrix(List<MatrixJob> jobs) { }

    public record MatrixJob(
            String name,
            String config
    ) {

        public MatrixJob(String name, MatrixJobConfig config) {
            this(name, GSON_MINIFIED.toJson(config));
        }

        public MatrixJobConfig parseConfig() {
            return GSON.fromJson(config(), MatrixJobConfig.class);
        }
    }

    public record MatrixJobConfig(
            List<JobVersion> versions,
            String decompilerVersion
    ) {

        public record JobVersion(String id, String commitName) { }
    }
}
