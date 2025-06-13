package net.javasauce.ss.tasks;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.util.VersionListManifest;
import net.javasauce.ss.util.VersionManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 1/21/25.
 */
public class VersionManifestTasks {

    public static List<VersionManifest> allVersionsWithMappings(SnowShovel ss, List<String> versionFilter, boolean doUpdateIfExists) throws IOException {
        VersionListManifest versionList = VersionListManifest.update(ss.http, ss.cacheDir, doUpdateIfExists);
        var futures = FastStream.of(versionList.versions())
                .filter(e -> versionFilter.isEmpty() || versionFilter.contains(e.id()))
                .map(e -> VersionManifest.updateFuture(ss.http, ss.cacheDir, e, doUpdateIfExists))
                .toList();

        return FastStream.of(futures)
                .map(CompletableFuture::join)
                .filter(e -> e.hasDownload("client") && e.hasDownload("client_mappings"))
                .reversed()
                .toList();
    }
}
