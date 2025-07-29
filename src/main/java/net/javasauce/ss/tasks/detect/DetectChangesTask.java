package net.javasauce.ss.tasks.detect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.ProcessableVersionSet;
import net.javasauce.ss.util.RunRequest;
import net.javasauce.ss.util.ToolUtils;
import net.javasauce.ss.util.VersionRequest;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/16/25.
 */
public class DetectChangesTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(DetectChangesTask.class);

    private static final String TAG_SNOW_SHOVEL_VERSION = "SnowShovelVersion";
    private static final String TAG_DECOMPILER_VERSION = "DecompilerVersion";

    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public final TaskInput<HttpEngine> http = input("http");
    public final TaskInput<Path> cacheDir = input("cacheDir");

    public final TaskInput<List<String>> versionFilters = input("versionFilters");
    public final TaskInput<Optional<String>> decompilerOverride = optionalInput("decompilerOverride");
    public final TaskInput<Boolean> simulateFullRun = input("simulateFullRun");

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
        var versionsFile = cacheDir.get().resolve("versions.json");
        var versions = loadVersions(versionsFile);
        ProcessableVersionSet versionSet = new ProcessableVersionSet(http.get(), cacheDir.get());
        this.versionSet.set(versionSet);

        RunRequest request;
        if (!simulateFullRun.get()) {
            request = detectAutomaticChanges(versionSet, versions);
        } else {
            LOGGER.info("Running in Manual mode. Re-processing everything.");
            var versionFilters = this.versionFilters.get();
            request = new RunRequest(
                    "Manual run: Process all versions.",
                    chooseDecompilerVersion(versions, decompilerOverride.get().orElse(null)),
                    FastStream.of(versionSet.allVersions())
                            .filter(e -> versionFilters.isEmpty() || versionFilters.contains(e))
                            .map(e -> new VersionRequest(e, "Manual run: Process all versions."))
                            .toList()
            );
        }
        if (request != null) {
            versions.put(TAG_SNOW_SHOVEL_VERSION, SnowShovel.VERSION);
            versions.put(TAG_DECOMPILER_VERSION, request.decompilerVersion());
            saveVersions(versionsFile, versions);
        }
        runRequest.set(Optional.ofNullable(request));
    }

    private @Nullable RunRequest detectAutomaticChanges(ProcessableVersionSet versionSet, Map<String, String> versions) throws IOException {
        LOGGER.info("Running in Automatic mode.");
        RunRequest request = detectSelfChanges(versionSet, versions);
        if (request == null) {
            request = detectMinecraftChanges(versionSet, versions);
        }
        if (request == null) {
            request = detectDecompilerChanges(versionSet, versions);
        }

        if (request == null) return null;

        return new RunRequest(
                "Automatic run: " + request.reason(),
                request.decompilerVersion(),
                request.versions()
        );
    }

    private @Nullable RunRequest detectSelfChanges(ProcessableVersionSet versionSet, Map<String, String> versions) {
        LOGGER.info("Checking for changes to SnowShovel version since last run..");
        var prev = versions.get(TAG_DECOMPILER_VERSION);
        if (SnowShovel.VERSION.equals(prev)) return null;

        var commit = "SnowShovel updated from " + prev + " to " + SnowShovel.VERSION;
        return new RunRequest(
                commit,
                chooseDecompilerVersion(versions, null),
                FastStream.of(versionSet.allVersions())
                        .map(e -> new VersionRequest(e, commit))
                        .toList()
        );
    }

    private @Nullable RunRequest detectMinecraftChanges(ProcessableVersionSet versionSet, Map<String, String> versions) throws IOException {
        LOGGER.info("Checking for changes to Minecraft versions since last run..");
        var changedVersions = versionSet.update();
        if (changedVersions.isEmpty()) return null;

        LOGGER.info(" The following versions changed: {}", FastStream.of(changedVersions).map(ProcessableVersionSet.ChangedVersion::id).toList());

        return new RunRequest(
                "New Minecraft versions or changes.",
                chooseDecompilerVersion(versions, null),
                FastStream.of(changedVersions)
                        .map(e -> new VersionRequest(e.id(), e.reason() == ProcessableVersionSet.ChangeReason.NEW ? "New version: " + e.id() : "Version manifest changed."))
                        .toList()
        );
    }

    private @Nullable RunRequest detectDecompilerChanges(ProcessableVersionSet versionSet, Map<String, String> versions) {
        LOGGER.info("Checking for changes to Decompiler version since last run..");

        var prev = versions.get(TAG_DECOMPILER_VERSION);
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

    private String chooseDecompilerVersion(Map<String, String> versions, @Nullable String decompilerVersion) {
        if (decompilerVersion != null) return decompilerVersion;

        decompilerVersion = versions.get(TAG_DECOMPILER_VERSION);
        if (decompilerVersion != null) return decompilerVersion;

        return getLatestDecompiler();
    }

    private String getLatestDecompiler() {
        return ToolUtils.findLatest(
                http.get(),
                "https://maven.covers1624.net",
                SnowShovel.DECOMPILER_TEMPLATE
        );
    }

    private static Map<String, String> loadVersions(Path versionsFile) throws IOException {
        Map<String, String> properties = null;
        if (Files.exists(versionsFile)) {
            properties = JsonUtils.parse(GSON, versionsFile, MAP_STRING_TYPE, StandardCharsets.UTF_8);
        }
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
        return properties;
    }

    private static void saveVersions(Path versionsFile, Map<String, String> versions) throws IOException {
        JsonUtils.write(GSON, IOUtils.makeParents(versionsFile), versions, MAP_STRING_TYPE, StandardCharsets.UTF_8);
    }
}
