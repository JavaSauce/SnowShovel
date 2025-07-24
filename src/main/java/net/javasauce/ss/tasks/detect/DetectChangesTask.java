package net.javasauce.ss.tasks.detect;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.*;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/16/25.
 */
public class DetectChangesTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(DetectChangesTask.class);

    public final TaskInput<HttpEngine> http = input("http");
    public final TaskInput<Path> cacheDir = input("cacheDir");

    public final TaskInput<List<String>> versionFilters = input("versionFilters");
    public final TaskInput<Optional<String>> decompilerOverride = optionalInput("decompilerOverride");
    public final TaskInput<Boolean> simulateFullRun = input("simulateFullRun");

    public final TaskOutput<RepoProperties> repoProperties = computedOutput("repoProperties");
    public final TaskOutput<ProcessableVersionSet> versionSet = computedOutput("versionSet");
    public final TaskOutput<Optional<RunRequest>> runRequest = computedOutput("runRequest");

    private DetectChangesTask(String name, Executor executor) {
        super(name, executor);
    }

    public static DetectChangesTask create(String name, Executor executor, Consumer<DetectChangesTask> cons) {
        var task = new DetectChangesTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        RepoProperties repoProperties = new RepoProperties(cacheDir.get());
        ProcessableVersionSet versionSet = new ProcessableVersionSet(http.get(), cacheDir.get());
        this.versionSet.set(versionSet);
        this.repoProperties.set(repoProperties);

        RunRequest request;
        if (!simulateFullRun.get()) {
            request = detectAutomaticChanges(versionSet);
            if (request != null) {
                repoProperties.setValue(RepoProperties.TAG_SNOW_SHOVEL_VERSION, SnowShovel.VERSION);
                repoProperties.setValue(RepoProperties.TAG_DECOMPILER_VERSION, request.decompilerVersion());
                repoProperties.save();
            }
        } else {
            LOGGER.info("Running in Manual mode. Re-processing everything.");
            var versionFilters = this.versionFilters.get();
            request = new RunRequest(
                    "Manual run: Process all versions.",
                    chooseDecompilerVersion(decompilerOverride.get().orElse(null)),
                    FastStream.of(versionSet.allVersions())
                            .filter(e -> versionFilters.isEmpty() || versionFilters.contains(e))
                            .map(e -> new VersionRequest(e, "Manual run: Process all versions."))
                            .toList()
            );
        }
        runRequest.set(Optional.ofNullable(request));
    }

    private @Nullable RunRequest detectAutomaticChanges(ProcessableVersionSet versionSet) throws IOException {
        LOGGER.info("Running in Automatic mode.");
        RunRequest request = detectSelfChanges(versionSet);
        if (request == null) {
            request = detectMinecraftChanges(versionSet);
        }
        if (request == null) {
            request = detectDecompilerChanges(versionSet);
        }

        if (request == null) return null;

        return new RunRequest(
                "Automatic run: " + request.reason(),
                request.decompilerVersion(),
                request.versions()
        );
    }

    private @Nullable RunRequest detectSelfChanges(ProcessableVersionSet versionSet) {
        LOGGER.info("Checking for changes to SnowShovel version since last run..");
        var prev = repoProperties.get().getValue(RepoProperties.TAG_DECOMPILER_VERSION);
        if (SnowShovel.VERSION.equals(prev)) return null;

        var commit = "SnowShovel updated from " + prev + " to " + SnowShovel.VERSION;
        return new RunRequest(
                commit,
                chooseDecompilerVersion(null),
                FastStream.of(versionSet.allVersions())
                        .map(e -> new VersionRequest(e, commit))
                        .toList()
        );
    }

    private @Nullable RunRequest detectMinecraftChanges(ProcessableVersionSet versionSet) throws IOException {
        LOGGER.info("Checking for changes to Minecraft versions since last run..");
        var changedVersions = versionSet.update();
        if (changedVersions.isEmpty()) return null;

        LOGGER.info(" The following versions changed: {}", FastStream.of(changedVersions).map(ProcessableVersionSet.ChangedVersion::id).toList());

        return new RunRequest(
                "New Minecraft versions or changes.",
                chooseDecompilerVersion(null),
                FastStream.of(changedVersions)
                        .map(e -> new VersionRequest(e.id(), e.reason() == ProcessableVersionSet.ChangeReason.NEW ? "New version: " + e.id() : "Version manifest changed."))
                        .toList()
        );
    }

    private @Nullable RunRequest detectDecompilerChanges(ProcessableVersionSet versionSet) {
        LOGGER.info("Checking for changes to Decompiler version since last run..");

        var prev = repoProperties.get().getValue(RepoProperties.TAG_DECOMPILER_VERSION);
        String latestDecompiler = getLatestDecompiler();
        if (latestDecompiler.equals(prev)) return null;

        var commit = "Decompiler updated from " + prev + " to " + latestDecompiler;
        return new RunRequest(
                commit,
                latestDecompiler,
                FastStream.of(versionSet.allVersions())
                        .map(e -> new VersionRequest(e, commit))
                        .toList()
        );
    }

    private String chooseDecompilerVersion(@Nullable String decompilerVersion) {
        if (decompilerVersion != null) return decompilerVersion;

        decompilerVersion = repoProperties.get().getValue(RepoProperties.TAG_DECOMPILER_VERSION);
        if (decompilerVersion != null) return decompilerVersion;

        return getLatestDecompiler();
    }

    private String getLatestDecompiler() {
        return ToolUtils.findLatest(
                http.get(),
                "https://maven.covers1624.net",
                MavenNotation.parse("net.javasauce:Decompiler:0:testframework@zip") // TODO const
        );
    }

}
