package net.javasauce.ss;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.covers1624.curl4j.CABundle;
import net.covers1624.curl4j.httpapi.Curl4jHttpEngine;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.DecompileTask;
import net.javasauce.ss.tasks.DownloadTask;
import net.javasauce.ss.tasks.PrepareToolTask;
import net.javasauce.ss.tasks.RemapperTask;
import net.javasauce.ss.tasks.detect.DetectChangesTask;
import net.javasauce.ss.tasks.git.*;
import net.javasauce.ss.tasks.report.DiscordReportTask;
import net.javasauce.ss.tasks.report.GenerateComparisonsTask;
import net.javasauce.ss.tasks.util.*;
import net.javasauce.ss.util.*;
import net.javasauce.ss.util.matrix.JobMatrix;
import net.javasauce.ss.util.task.Task;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import static java.util.List.of;

/**
 * Created by covers1624 on 1/19/25.
 */
public class SnowShovel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowShovel.class);

    private static final @Nullable String DISCORD_WEBHOOK = System.getenv("DISCORD_WEBHOOK");

    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            new BasicThreadFactory.Builder()
                    .namingPattern("Download executor %d")
                    .daemon(true)
                    .build()
    );

    // Single thread executor to bottleneck the Remapper tasks through,
    // This is mostly for log clarity, so its logs aren't intertwined with others.
    private static final ExecutorService REMAPPER_EXECUTOR = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Remapper Thread")
            .daemon(true)
            .build()
    );

    // Single thread executor to bottleneck the Decompile tasks through,
    // as the decompiler does its own threading in its spawned process
    private static final ExecutorService DECOMPILE_EXECUTOR = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Decompiler Thread")
            .daemon(true)
            .build()
    );

    // Single thread executor to ensure all git operations happen sequentially.
    private static final ExecutorService GIT_EXECUTOR = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
            .namingPattern("Git Thread")
            .daemon(true)
            .build()
    );

    private static final Map<JavaVersion, SetupJdkTask> JDK_TASKS = new HashMap<>();

    public static final MavenNotation FAST_REMAPPER_VERSION = MavenNotation.parse("net.covers1624:FastRemapper:0.3.2.23@zip");
    public static final MavenNotation DECOMPILER_TEMPLATE = MavenNotation.parse("net.javasauce:Decompiler:0:test-engine@zip");
    public static final String VERSION;

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

        var runMatrixOpt = runMatrixBuilder
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

        String repoUrl = optSet.valueOf(gitRepoOpt);
        String gitUser = System.getenv("GIT_USER");
        String gitPass = System.getenv("GIT_PASS");
        if (gitUser == null || gitPass == null) {
            LOGGER.error("GIT_USER and GIT_PASS environment variables are required.");
            System.exit(1);
            return;
        }
        CredentialsProvider.setDefault(new UsernamePasswordCredentialsProvider(gitUser, gitPass));

        var workDir = Path.of(".").toAbsolutePath().normalize();
        var versionsDir = workDir.resolve("versions");
        var librariesDir = workDir.resolve("libraries");
        var toolsDir = workDir.resolve("tools");
        var tempDir = workDir.resolve("temp");

        var repoDir = workDir.resolve("repo");
        var http = new Curl4jHttpEngine(CABundle.builtIn());
        var jdkProvider = new JdkProvider(toolsDir.resolve("jdks/"), http);

        var shouldPush = optSet.has(gitPushOpt);
        var shouldClean = optSet.has(gitCleanOpt);

        var simulateFullRun = optSet.has(simulateFullRunOpt);
        var decompilerOverride = Optional.ofNullable(optSet.valueOf(decompilerVersionOpt));
        var mcVersionOverride = optSet.valuesOf(versionOpt);

        if (Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new DeleteHierarchyVisitor());
        }

        // Stage 0, Setup Git
        var gitSetupTask = SetupGitRepoTask.create("setupGit", GIT_EXECUTOR, task -> {
            task.repoDir.set(repoDir);
            task.repoUrl.set(repoUrl);
            task.clearClone.set(shouldClean);
        });

        var checkoutMain = CheckoutBranchTask.create("checkoutMain", GIT_EXECUTOR, task -> {
            task.git.set(gitSetupTask.output);
            task.branch.set("main");
        });
        Task.runTasks(checkoutMain);

        var git = gitSetupTask.output.get();
        try (git; DOWNLOAD_EXECUTOR; REMAPPER_EXECUTOR; DECOMPILE_EXECUTOR; GIT_EXECUTOR) {
            if (optSet.has(genMatrixOpt)) {
                var stage1 = runStage1(http, repoDir, gitSetupTask, simulateFullRun, mcVersionOverride, decompilerOverride, shouldPush);
                if (stage1 == null) {
                    LOGGER.info("No changes.");
                    return;
                }
                if (shouldPush) {
                    var pushTask = PushAllTask.create("pushAllTags", GIT_EXECUTOR, task -> {
                        task.git.set(gitSetupTask.output);
                        task.tags.set(true);
                    });
                    Task.runTasks(pushTask);
                }

                var matrix = RunRequest.splitJobs(stage1.runRequest, optSet.valueOf(matrixSizeOpt));
                JobMatrix.write(optSet.valueOf(genMatrixOpt), matrix);
                return;
            }
            if (optSet.has(runMatrixOpt)) {
                // Fast-forward main to our tag, we are in matrix mode and don't push branch changes, this is fine.
                var fastForwardMain = FastForwardTask.create("fastForwardMain", GIT_EXECUTOR, task -> {
                    task.git.set(gitSetupTask.output);
                    task.branch.set("main");
                    task.tag.set(Optional.of("temp/main"));
                });
                Task.runTasks(fastForwardMain);

                var versionSet = new ProcessableVersionSet(http, repoDir.resolve("cache"));
                versionSet.allVersions();

                var runRequest = RunRequest.parse(optSet.valueOf(runMatrixOpt));
                runStage2(http, jdkProvider, toolsDir, librariesDir, versionsDir, tempDir, repoDir, runRequest, versionSet, gitSetupTask, shouldPush, repoUrl);
                return;
            }
            if (optSet.has(finalizeMatrixOpt)) {
                // Fast-forward main to our tag, we are in matrix mode and don't push branch changes, this is fine.
                var fastForwardMain = FastForwardTask.create("fastForwardMain", GIT_EXECUTOR, task -> {
                    task.git.set(gitSetupTask.output);
                    task.branch.set("main");
                    task.tag.set(Optional.of("temp/main"));
                });
                Task.runTasks(fastForwardMain);

                var versionSet = new ProcessableVersionSet(http, repoDir.resolve("cache"));
                var matrix = JobMatrix.parse(optSet.valueOf(finalizeMatrixOpt));
                var runRequest = RunRequest.mergeJobs(matrix);
                runStage3(http, repoDir, runRequest, versionSet, gitSetupTask, shouldPush, repoUrl);
                return;
            }

            var stage1 = runStage1(http, repoDir, gitSetupTask, simulateFullRun, mcVersionOverride, decompilerOverride, shouldPush);
            if (stage1 == null) {
                LOGGER.info("No changes.");
                return;
            }

            runStage2(http, jdkProvider, toolsDir, librariesDir, versionsDir, tempDir, repoDir, stage1.runRequest, stage1.versionSet, gitSetupTask, shouldPush, repoUrl);
            runStage3(http, repoDir, stage1.runRequest, stage1.versionSet, gitSetupTask, shouldPush, repoUrl);
        }
        LOGGER.info("Done!");
    }

    private static @Nullable Stage1Pair runStage1(
            HttpEngine http,
            Path repoDir,
            SetupGitRepoTask gitSetupTask,
            boolean simulateFullRun,
            List<String> mcVersionOverride,
            Optional<String> decompilerOverride,
            boolean shouldPush
    ) throws IOException {
        // Stage 1, Detect changes.
        var detectChanges = DetectChangesTask.create("detectChanges", ForkJoinPool.commonPool(), task -> {
            task.http.set(http);
            task.cacheDir.set(repoDir.resolve("cache"));
            task.versionFilters.set(mcVersionOverride);
            task.decompilerOverride.set(decompilerOverride);
            task.simulateFullRun.set(simulateFullRun);
        });

        Task.runTasks(detectChanges);

        // Stage 1.5, Check if redundant, tag main with cache.
        var runRequest = detectChanges.runRequest.get().orElse(null);
        if (runRequest == null) return null;

        var versionSet = detectChanges.versionSet.get();

        var tempTagMain = CommitTask.create("tagMain", GIT_EXECUTOR, task -> {
            task.git.set(gitSetupTask.output);
            task.commitMessage.set(Optional.of(runRequest.reason()));
            task.tagName.set(Optional.of("temp/main"));
        });

        var pushMainTagBarrier = new BarrierTask("pushMainTag");
        pushMainTagBarrier.dependsOn(tempTagMain);
        if (shouldPush) {
            // TODO make this more granular.
            //      We can probably refactor PushAllTask to just take a list of refs to push.
            var pushTask = PushAllTask.create("pushMainTag", GIT_EXECUTOR, task -> {
                task.dependsOn(tempTagMain);
                task.git.set(gitSetupTask.output);
                task.tags.set(true);
            });
            pushMainTagBarrier.dependsOn(pushTask);
        }
        Task.runTasks(pushMainTagBarrier);

        if (DISCORD_WEBHOOK != null) {
            new DiscordWebhook(DISCORD_WEBHOOK)
                    .setContent("SnowShovel run starting, processing " + runRequest.versions().size() + " versions.")
                    .execute(http);
        }
        return new Stage1Pair(runRequest, versionSet);
    }

    private record Stage1Pair(
            RunRequest runRequest,
            ProcessableVersionSet versionSet
    ) { }

    private static void runStage2(
            HttpEngine http,
            JdkProvider jdkProvider,
            Path toolsDir,
            Path librariesDir,
            Path versionsDir,
            Path tempDir,
            Path repoDir,
            RunRequest runRequest,
            ProcessableVersionSet versionSet,
            SetupGitRepoTask gitSetupTask,
            boolean shouldPush,
            String repoUrl
    ) {

        // Stage 2
        var prepareRemapper = PrepareToolTask.create("prepareRemapper", DOWNLOAD_EXECUTOR, http, task -> {
            task.notation.set(FAST_REMAPPER_VERSION);
            task.toolDir.set(toolsDir);
        });

        var prepareDecompiler = PrepareToolTask.create("prepareDecompiler", DOWNLOAD_EXECUTOR, http, task -> {
            task.notation.set(DECOMPILER_TEMPLATE.withVersion(runRequest.decompilerVersion()));
            task.toolDir.set(toolsDir);
        });

        var downloadGradleWrapper = DownloadTask.create("downloadGradleWrapper", DOWNLOAD_EXECUTOR, http, task -> {
            task.output.set(librariesDir.resolve("GradleWrapper.zip"));
            task.url.set("https://covers1624.net/Files/GradleWrapper-8.10.2.zip");
            task.downloadLen.set(44825L);
            task.downloadHash.set(Optional.of("2e355d2ede2307bfe40330db29f52b9b729fd9b2"));
        });

        Map<LibraryDownload, DownloadTask> libraryDownloads = new HashMap<>();

        var gitTagAllBarrier = new BarrierTask("gitTagAllBarrier");
        for (var version : runRequest.versions()) {
            var id = version.id();
            var manifest = versionSet.getManifest(id);

            var downloadClient = DownloadTask.create("downloadClient_" + id, DOWNLOAD_EXECUTOR, http, task -> {
                var download = manifest.downloads().get("client");
                task.output.set(versionsDir.resolve(id).resolve(id + "-client.jar"));
                task.url.set(download.url());
                task.downloadHash.set(Optional.of(download.sha1()));
                task.downloadLen.set(download.size());
            });

            var downloadClientMappings = DownloadTask.create("downloadClientMappings_" + id, DOWNLOAD_EXECUTOR, http, task -> {
                var download = manifest.downloads().get("client_mappings");
                task.output.set(versionsDir.resolve(id).resolve(id + "-client_mappings.jar"));
                task.url.set(download.url());
                task.downloadHash.set(Optional.of(download.sha1()));
                task.downloadLen.set(download.size());
            });

            var remapClient = RemapperTask.create("remapClient_" + id, REMAPPER_EXECUTOR, task -> {
                task.tool.set(prepareRemapper.output);
                task.javaHome.set(getJdkTask(jdkProvider, JavaVersion.JAVA_17).javaHome);
                task.input.set(downloadClient.output);
                task.mappings.set(downloadClientMappings.output);
                task.remapped.set(versionsDir.resolve(id).resolve(id + "-client-remapped.jar"));
            });

            var libDefs = LibraryDownload.getVersionLibraries(manifest, librariesDir);
            List<DownloadTask> libraries = FastStream.of(libDefs)
                    .map(library -> libraryDownloads.computeIfAbsent(library, e2 ->
                            DownloadTask.create("downloadLibrary_" + library.notation(), DOWNLOAD_EXECUTOR, http, task -> {
                                task.url.set(library.url());
                                task.output.set(library.path());
                                task.downloadHash.set(Optional.ofNullable(library.sha1()));
                                task.downloadLen.set(library.size());
                            })))
                    .toList();

            var decompileTask = DecompileTask.create("decompile_" + id, DECOMPILE_EXECUTOR, task -> {
                task.javaRuntimeHome.set(getJdkTask(jdkProvider, pickDecompilerJavaVersion(JavaVersion.JAVA_21, manifest.computeJavaVersion())).javaHome);
                task.javaReferenceHome.set(getJdkTask(jdkProvider, manifest.computeJavaVersion()).javaHome);
                task.tool.set(prepareDecompiler.output);
                task.libraries.set(FastStream.of(libraries).map(e -> e.output).toList());
                task.inputJar.set(remapClient.remapped);
                task.output.set(tempDir.resolve(id));
            });

            var branchName = manifest.computeBranchName();
            var checkoutBranchTask = CheckoutBranchTask.create("checkout_" + id, GIT_EXECUTOR, task -> {
                task.dependsOn(decompileTask);
                task.git.set(gitSetupTask.output);
                task.branch.set(branchName);
                task.clean.set(true);
            });

            var copyTask = CopyTask.create("copyDecompileResults_" + id, GIT_EXECUTOR, task -> {
                task.dependsOn(checkoutBranchTask);
                task.input.set(decompileTask.output);
                task.output.set(repoDir);
            });

            // TODO we can run this in parallel with copy, but not due to the executors they use.
            //      We can probably also move away from using a gradle wrapper dist zip now, and just run gradle to gen a wrapper
            //      we only ever used the dist zip because it was faster than stalling the program waiting for Gradle.
            var genProjectTask = GenerateGradleProjectTask.create("generateGradleProject_" + id, GIT_EXECUTOR, task -> {
                task.dependsOn(checkoutBranchTask);
                task.projectDir.set(repoDir);
                task.gradleWrapperDist.set(downloadGradleWrapper.output);
                task.javaVersion.set(manifest.computeJavaVersion());
                task.libraries.set(libDefs);
                task.mcManifest.set(manifest);
                task.gitRepoUrl.set(repoUrl);
            });

            var commitTask = CommitTask.create("commitAndTag_" + id, GIT_EXECUTOR, task -> {
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
            var pushTask = PushAllTask.create("pushAllTags", GIT_EXECUTOR, task -> {
                task.dependsOn(gitTagAllBarrier);
                task.git.set(gitSetupTask.output);
                task.tags.set(true);
            });
            pushAllTagsBarrier.dependsOn(pushTask);
        }

        Task.runTasks(pushAllTagsBarrier);
    }

    private static void runStage3(
            HttpEngine http,
            Path repoDir,
            RunRequest runRequest,
            ProcessableVersionSet versionSet,
            SetupGitRepoTask gitSetupTask,
            boolean shouldPush,
            String repoUrl
    ) throws IOException {
        // Stage 3
        var fastForwardBarrier = new BarrierTask("fastForwardBarrier");
        List<String> tagsToDelete = new ArrayList<>();
        for (var version : runRequest.versions()) {
            var manifest = versionSet.getManifest(version.id());
            var branch = manifest.computeBranchName();
            var tag = "temp/" + branch;
            var fastForward = FastForwardTask.create("fastForward_" + version.id(), GIT_EXECUTOR, task -> {
                task.git.set(gitSetupTask.output);
                task.branch.set(branch);
                task.tag.set(Optional.of(tag));
            });
            tagsToDelete.add(tag);
            fastForwardBarrier.dependsOn(fastForward);
        }

        var fastForwardMain = FastForwardTask.create("fastForwardMain", GIT_EXECUTOR, task -> {
            task.dependsOn(fastForwardBarrier);
            task.git.set(gitSetupTask.output);
            task.branch.set("main");
            task.tag.set(Optional.of("temp/main"));
        });
        tagsToDelete.add("temp/main");

        var extractStats = ExtractTestStatsTask.create("extractTestStats", GIT_EXECUTOR, task -> {
            task.dependsOn(fastForwardMain);
            task.git.set(gitSetupTask.output);
            task.versionSet.set(versionSet);
        });

        var genRootProject = GenerateRootProjectTask.create("genRootProject", ForkJoinPool.commonPool(), task -> {
            task.dependsOn(fastForwardMain);
            task.projectDir.set(repoDir);
            task.versions.set(versionSet);
            task.testDefs.set(extractStats.testStats);
            task.gitRepoUrl.set(repoUrl);
        });

        var amendMain = CommitTask.create("amendMain", GIT_EXECUTOR, task -> {
            task.dependsOn(genRootProject);
            task.git.set(gitSetupTask.output);
            task.amend.set(true);
        });

        var pushBarrier = new BarrierTask("pushBarrier");
        pushBarrier.dependsOn(amendMain);

        if (shouldPush) {
            var pushTask = PushAllTask.create("pushAllBranches", GIT_EXECUTOR, task -> {
                task.git.set(gitSetupTask.output);
                task.dependsOn(amendMain);
                task.branches.set(true);
            });
            pushBarrier.dependsOn(pushTask);
        }

        var deleteTags = DeleteTagsTask.create("deleteTags", GIT_EXECUTOR, task -> {
            task.dependsOn(pushBarrier);
            task.git.set(gitSetupTask.output);
            task.tagNames.set(tagsToDelete);
            task.local.set(true);
            task.remote.set(shouldPush);
        });

        var discordPostBarrier = new BarrierTask("discordPostBarrier");
        if (DISCORD_WEBHOOK != null) {
            var genComparisons = GenerateComparisonsTask.create("genComparisons", ForkJoinPool.commonPool(), task -> {
                task.dependsOn(pushBarrier);
                task.stats.set(extractStats.testStats);
            });

            var discordReport = DiscordReportTask.create("discordReport", ForkJoinPool.commonPool(), task -> {
                task.webhook.set(DISCORD_WEBHOOK);
                task.gitRepoUrl.set(repoUrl);
                task.http.set(http);
                task.versions.set(versionSet.allVersions());
                task.postDefs.set(extractStats.testStats);
                task.comparisons.set(genComparisons.comparisons);
            });
            discordPostBarrier.dependsOn(discordReport);
        }

        Task.runTasks(pushBarrier, deleteTags, discordPostBarrier);
        if (DISCORD_WEBHOOK != null) {
            new DiscordWebhook(DISCORD_WEBHOOK)
                    .setContent("SnowShovel run finished, processed " + runRequest.versions().size() + " versions.")
                    .execute(http);
        }
    }

    private static SetupJdkTask getJdkTask(JdkProvider jdkProvider, JavaVersion javaVersion) {
        return JDK_TASKS.computeIfAbsent(javaVersion, e ->
                SetupJdkTask.create("provisionJdk_" + javaVersion.shortString, DOWNLOAD_EXECUTOR, jdkProvider, task -> {
                    task.javaVersion.set(javaVersion);
                })
        );
    }

    private static JavaVersion pickDecompilerJavaVersion(JavaVersion a, JavaVersion b) {
        if (a.ordinal() > b.ordinal()) return a;

        return b;
    }
}
