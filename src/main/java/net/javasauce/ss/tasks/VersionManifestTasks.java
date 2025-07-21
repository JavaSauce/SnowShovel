package net.javasauce.ss.tasks;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.IgnoredVersions;
import net.javasauce.ss.util.VersionListManifest;
import net.javasauce.ss.util.VersionManifest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 1/21/25.
 */
@Deprecated
public class VersionManifestTasks {

    public static @Nullable ChangedVersionResult changedVersions(SnowShovel ss) throws IOException {
        var manifestFile = ss.cacheDir.resolve(VersionListManifest.FILE_NAME);
        if (Files.notExists(manifestFile)) return null;

        var oldDownload = DownloadTasks.InMemoryDownload.readFrom(manifestFile);
        var newDownload = DownloadTasks.inMemoryDownload(ss.http, VersionListManifest.URL, oldDownload);

        if (newDownload.isUpToDate()) return null;
        VersionListManifest.save(newDownload, manifestFile);

        var oldManifest = VersionListManifest.loadFrom(oldDownload.toString());
        var newManifest = VersionListManifest.loadFrom(newDownload.toString());

        var oldMap = oldManifest.versionsMap();

        var changedVersions = FastStream.of(newManifest.versions())
                .filterNot(e -> IgnoredVersions.IGNORED_VERSION.contains(e.id()))
                .filter(e -> !oldMap.containsKey(e.id()) || !e.url().equals(oldMap.get(e.id()).url()))
                .reversed() // Oldest first.
                .toList();

        return new ChangedVersionResult(
                changedVersions,
                FastStream.of(changedVersions)
                        .filter(e -> !oldMap.containsKey(e.id()))
                        .toSet()
        );
    }

    public static List<VersionListManifest.Version> allVersions(SnowShovel ss) throws IOException {
        var manifestFile = ss.cacheDir.resolve(VersionListManifest.FILE_NAME);
        if (Files.notExists(manifestFile)) {
            var download = DownloadTasks.inMemoryDownload(ss.http, VersionListManifest.URL, null);
            VersionListManifest.save(download, manifestFile);
        }

        var manifest = VersionListManifest.loadFrom(Files.readString(manifestFile, StandardCharsets.UTF_8));

        return FastStream.of(manifest.versions())
                .filterNot(e -> IgnoredVersions.IGNORED_VERSION.contains(e.id()))
                .toList();
    }

    public static FastStream<VersionManifest> getManifests(SnowShovel ss, FastStream<VersionListManifest.Version> versions) {
        var futures = versions
                .map(e -> VersionManifest.updateFuture(ss.http, ss.cacheDir, e))
                .toList();

        return FastStream.of(futures)
                .map(CompletableFuture::join)
                .filter(e -> e.hasDownload("client") && e.hasDownload("client_mappings"))
                .reversed(); // Oldest first
    }

    public record ChangedVersionResult(List<VersionListManifest.Version> versions, Set<VersionListManifest.Version> newVersions) {
    }
}
