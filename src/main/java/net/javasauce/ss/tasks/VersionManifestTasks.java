package net.javasauce.ss.tasks;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.util.VersionListManifest;
import net.javasauce.ss.util.VersionManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 1/21/25.
 */
public class VersionManifestTasks {


    public static List<VersionManifest> getVersionManifests(HttpEngine http, Path versionsDir, List<String> versionIds) throws IOException {
        VersionListManifest versionList = VersionListManifest.update(http, versionsDir);
        Map<String, VersionListManifest.Version> mapLookup = versionList.versionsMap();
        List<VersionListManifest.Version> versions = new ArrayList<>(versionIds.size());
        for (String versionId : versionIds) {
            VersionListManifest.Version version = mapLookup.get(versionId);
            if (version == null) {
                throw new RuntimeException("Version " + versionId + " is not a valid version.");
            }
            versions.add(version);
        }

        List<CompletableFuture<VersionManifest>> manifestFutures = FastStream.of(versions)
                .map(version -> VersionManifest.updateFuture(http, versionsDir, version))
                .toList();
        return FastStream.of(manifestFutures)
                .map(CompletableFuture::join)
                .toList();
    }
}
