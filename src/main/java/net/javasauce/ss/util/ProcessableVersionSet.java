package net.javasauce.ss.util;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.DownloadTask;
import net.javasauce.ss.util.task.Task;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static java.util.Objects.requireNonNull;

/**
 * A set of Minecraft versions.
 * <p>
 * Will use a previous version manifest if available, or download a fresh one.
 * <p>
 * Must be explicitly updated via {@link #update()} if a manifest is to be replaced.
 * <p>
 * Created by covers1624 on 7/19/25.
 */
public class ProcessableVersionSet {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpEngine http;
    private final Path cacheDir;
    private @Nullable VersionListManifest listManifest;

    private @Nullable List<String> allVersions;
    private @Nullable Map<String, VersionManifest> versionManifests;

    public ProcessableVersionSet(HttpEngine http, Path cacheDir) {
        this.http = http;
        this.cacheDir = cacheDir;
    }

    public List<ChangedVersion> update() throws IOException {
        if (allVersions != null || versionManifests != null) {
            throw new IllegalStateException("Unable to update version list once manifests have been resolved.");
        }

        var existingDownload = InMemoryDownload.readFrom(cacheDir.resolve("version_manifest_v2.json"));
        var newDownload = InMemoryDownload.doDownload(http, VERSION_MANIFEST_URL, existingDownload);
        if (newDownload.isUpToDate()) return List.of();

        var oldListManifest = existingDownload != null ? VersionListManifest.loadFrom(existingDownload.toString()) : null;
        var newListManifest = VersionListManifest.loadFrom(newDownload.toString());
        newDownload.writeTo(cacheDir.resolve("version_manifest_v2.json"));

        Map<String, VersionManifest> oldManifests = FastStream.ofNullable(oldListManifest)
                .flatMap(this::resolveManifests)
                .toMap(VersionManifest::id, e -> e);
        var newManifestsList = FastStream.of(newListManifest)
                .flatMap(this::resolveManifests)
                .toList();
        Map<String, VersionManifest> newManifests = FastStream.of(newManifestsList)
                .toLinkedHashMap(VersionManifest::id, e -> e);

        // Pre-populate these, so we don't re-load them later when asked for all versions.
        populateManifests(newManifestsList);

        List<ChangedVersion> changes = new ArrayList<>();
        for (VersionListManifest.Version version : newListManifest.versions()) {
            var id = version.id();
            if (IgnoredVersions.IGNORED_VERSION.contains(id)) continue;

            var newManifest = newManifests.get(id);
            if (newManifest == null) throw new RuntimeException("Missing manifest? " + id);

            var oldManifest = oldManifests.get(id);
            if (oldManifest == null) {
                changes.add(new ChangedVersion(ChangeReason.NEW, id));
            } else if (!newManifest.equals(oldManifest)) {
                changes.add(new ChangedVersion(ChangeReason.CHANGED, id));
            }
        }
        return changes;
    }

    public VersionListManifest listManifest() {
        if (listManifest == null) {
            try {
                var manifestDownload = InMemoryDownload.readFrom(cacheDir.resolve("version_manifest_v2.json"));
                if (manifestDownload == null) {
                    manifestDownload = InMemoryDownload.doDownload(http, VERSION_MANIFEST_URL, manifestDownload);
                }
                listManifest = VersionListManifest.loadFrom(manifestDownload.toString());
            } catch (IOException ex) {
                throw new RuntimeException("Failed to load manifest.", ex);
            }
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
        populateManifests(resolveManifests(listManifest()));
    }

    private void populateManifests(List<VersionManifest> manifests) {
        allVersions = List.copyOf(FastStream.of(manifests.reversed())
                .map(VersionManifest::id)
                .toList());
        versionManifests = Map.copyOf(FastStream.of(manifests.reversed())
                .toLinkedHashMap(VersionManifest::id, e -> e));
    }

    private List<VersionManifest> resolveManifests(VersionListManifest listManifest) {
        var downloads = FastStream.of(listManifest.versions())
                .filter(e -> !IgnoredVersions.IGNORED_VERSION.contains(e.id()))
                .map(version -> DownloadTask.create("downloadManifest_" + version.id(), ForkJoinPool.commonPool(), http, task -> {
                    task.url.set(version.url());
                    task.downloadHash.set(Optional.of(version.sha1()));
                    task.output.set(VersionManifest.pathForId(cacheDir, version.id()));
                    task.addMutator(JsonPretty::prettyPrintJsonFile);
                }))
                .toList();
        Task.runTasks(downloads);

        List<VersionManifest> manifests = new ArrayList<>();
        try {
            for (DownloadTask download : downloads) {
                manifests.add(VersionManifest.loadFrom(download.output.get()));
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read manifests.", ex);
        }
        return manifests;
    }

    public enum ChangeReason {
        CHANGED,
        NEW,
    }

    public record ChangedVersion(ChangeReason reason, String id) { }
}
