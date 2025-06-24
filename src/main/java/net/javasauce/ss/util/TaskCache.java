package net.javasauce.ss.util;

import net.covers1624.quack.util.SneakyUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 6/24/25.
 */
public class TaskCache {

    private final Path cacheFile;
    private final List<SneakyUtils.ThrowingConsumer<MessageDigest, IOException>> entries = new ArrayList<>();

    private TaskCache(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    public static TaskCache forOutput(Path outputPath) {
        return forOutput(outputPath, "");
    }

    public static TaskCache forOutput(Path outputPath, String suffix) {
        return new TaskCache(outputPath.resolveSibling(outputPath.getFileName() + suffix + ".sha1"));
    }

    public TaskCache add(Path file) {
        entries.add(e -> Hashing.tryAddFileBytes(e, file));
        return this;
    }

    public TaskCache add(CharSequence str) {
        entries.add(e -> Hashing.addUTFBytes(e, str.toString()));
        return this;
    }

    public TaskCache add(Number num) {
        entries.add(e -> Hashing.addUTFBytes(e, num.toString()));
        return this;
    }

    public TaskCache addNullable(@Nullable CharSequence str) {
        entries.add(e -> {
            if (str != null) {
                Hashing.addUTFBytes(e, str.toString());
            }
        });
        return this;
    }

    private String hash() throws IOException {
        var hasher = Hashing.digest(Hashing.SHA1);
        for (var entry : entries) {
            entry.accept(hasher);
        }
        return Hashing.toString(hasher);
    }

    public boolean isUpToDate() throws IOException {
        if (Files.notExists(cacheFile)) return false;

        String existing = Files.readString(cacheFile);
        return existing.equals(hash());
    }

    public void writeCache() throws IOException {
        Files.writeString(cacheFile, hash());
    }
}
