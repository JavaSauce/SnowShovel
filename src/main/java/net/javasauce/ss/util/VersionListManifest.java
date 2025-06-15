package net.javasauce.ss.util;

import com.google.gson.Gson;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.DownloadTasks;
import net.javasauce.ss.tasks.Tasks;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 24/10/24.
 */
public record VersionListManifest(
        @Nullable Latest latest,
        @Nullable List<Version> versions
) {

    private static final Gson GSON = new Gson();
    public static final String URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String FILE_NAME = "version_manifest_v2.json";

    public static void save(DownloadTasks.InMemoryDownload download, Path file) throws IOException {
        download.writeTo(file);
        JsonPretty.prettyPrintJsonFile(file);
    }

    public static VersionListManifest loadFrom(String str) {
        try {
            return JsonUtils.parse(GSON, str, VersionListManifest.class);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to parse manifest.", ex);
        }
    }

    @Override
    public Latest latest() {
        return requireNonNull(latest);
    }

    @Override
    public List<Version> versions() {
        return versions != null ? versions : List.of();
    }

    public Map<String, Version> versionsMap() {
        return FastStream.of(versions())
                .toMap(Version::id, Function.identity());
    }

    public record Latest(
            @Nullable String release,
            @Nullable String snapshot
    ) {

        // @formatter:off
        @Override public String release() { return requireNonNull(release); }
        @Override public String snapshot() { return requireNonNull(snapshot); }
        // @formatter:on
    }

    public record Version(
            @Nullable String id,
            @Nullable String type,
            @Nullable String url,
            @Nullable Date time,
            @Nullable Date releaseTime,
            @Nullable String sha1,
            int complianceLevel
    ) {

        // @formatter:off
        @Override public String id() { return requireNonNull(id); }
        @Override public String type() { return requireNonNull(type); }
        @Override public String url() { return requireNonNull(url); }
        @Override public Date time() { return requireNonNull(time); }
        @Override public Date releaseTime() { return requireNonNull(releaseTime); }
        @Override public String sha1() { return requireNonNull(sha1); }
        // @formatter:on
    }
}
