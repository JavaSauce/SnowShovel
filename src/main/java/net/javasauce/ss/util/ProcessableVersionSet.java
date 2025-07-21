package net.javasauce.ss.util;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.DownloadTasks;
import net.javasauce.ss.tasks.NewDownloadTask;
import net.javasauce.ss.util.task.Task;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 7/19/25.
 */
public class ProcessableVersionSet {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpEngine http;
    private final Path cacheDir;
    private @Nullable VersionListManifest listManifest;
    private @Nullable DownloadTasks.InMemoryDownload listManifestDownload;

    private @Nullable List<String> allVersions;
    private @Nullable Map<String, VersionManifest> versionManifests;

    public ProcessableVersionSet(HttpEngine http, Path cacheDir) throws IOException {
        this.http = http;
        this.cacheDir = cacheDir;
        if (Files.notExists(cacheDir)) return;

        Path listFile = cacheDir.resolve("version_manifest_v2.json");
        if (!Files.exists(listFile)) return;

        listManifestDownload = DownloadTasks.InMemoryDownload.readFrom(listFile);

        listManifest = VersionListManifest.loadFrom(listManifestDownload.toString());
    }

    public List<ChangedVersion> update() throws IOException {
        if (allVersions != null || versionManifests != null) {
            throw new IllegalStateException("Unable to update version list once manifests have been resolved.");
        }

        var newDownload = DownloadTasks.inMemoryDownload(http, VERSION_MANIFEST_URL, listManifestDownload);
        if (newDownload.isUpToDate()) return List.of();

        var oldManifest = listManifest;
        var newManifest = VersionListManifest.loadFrom(newDownload.toString());
        newDownload.writeTo(cacheDir.resolve("version_manifest_v2.json"));

        listManifestDownload = newDownload;

        Map<String, VersionListManifest.Version> oldMap = oldManifest != null ? oldManifest.versionsMap() : Map.of();

        List<ChangedVersion> changes = new ArrayList<>();
        for (VersionListManifest.Version version : newManifest.versions()) {
            var id = version.id();
            if (IgnoredVersions.IGNORED_VERSION.contains(id)) continue;

            if (!oldMap.containsKey(id)) {
                changes.add(new ChangedVersion(ChangeReason.NEW, id));
            } else if (!oldMap.get(id).url().equals(version.url())) { // TODO we should compare manifests, we don't care about asset updates.
                changes.add(new ChangedVersion(ChangeReason.CHANGED, id));
            }
        }
        return changes;
    }

    public VersionListManifest listManifest() {
        if (listManifest == null) {
            listManifestDownload = DownloadTasks.inMemoryDownload(http, VERSION_MANIFEST_URL, null);
            listManifest = VersionListManifest.loadFrom(listManifestDownload.toString());
        }
        return listManifest;
    }

    public List<String> allVersions() {
        if (allVersions == null) {
            populateManifests();
        }
        return requireNonNull(allVersions);
    }

    public VersionManifest getManifest(String id) {
        return requireNonNull(
                getVersionManifests().get(id),
                "Version not available? " + id
        );
    }

    public Map<String, VersionManifest> getVersionManifests() {
        if (versionManifests == null) {
            populateManifests();
        }
        return requireNonNull(versionManifests);
    }

    private void populateManifests() {
        // TODO this really shouldn't write to disk, we should save that for the `save()` function.
        var downloads = FastStream.of(listManifest().versions())
                .filter(e -> !IgnoredVersions.IGNORED_VERSION.contains(e.id()))
                // TODO perhaps we can move InMemoryDownload to a task and use that here, then also hold onto the task list?
                .map(version -> NewDownloadTask.create("downloadManifest_" + version.id(), ForkJoinPool.commonPool(), http, task -> {
                    task.url.set(version.url());
                    task.downloadHash.set(Optional.of(version.sha1()));
                    task.output.set(VersionManifest.pathForId(cacheDir, version.id()));
                    task.addMutator(JsonPretty::prettyPrintJsonFile);
                }))
                .toList();
        Task.runTasks(downloads);

        List<VersionManifest> manifests = new ArrayList<>();
        try {
            for (NewDownloadTask download : downloads) {
                manifests.add(VersionManifest.loadFrom(download.output.get()));
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read manifests.", ex);
        }

        // TODO verify that reversed here is right.
        allVersions = List.copyOf(FastStream.of(manifests.reversed())
                .map(VersionManifest::id)
                .toList());
        versionManifests = Map.copyOf(FastStream.of(manifests.reversed())
                .toLinkedHashMap(VersionManifest::id, e -> e));
    }

    public enum ChangeReason {
        CHANGED,
        NEW,
    }

    public record ChangedVersion(ChangeReason reason, String id) { }
}
