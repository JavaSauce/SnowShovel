package net.javasauce.ss.util.task;

import net.covers1624.quack.util.SneakyUtils;
import net.javasauce.ss.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Created by covers1624 on 6/24/25.
 */
public class TaskCacheBuilder {

    private final Path cacheFile;
    private final List<SneakyUtils.ThrowingConsumer<MessageDigest, IOException>> entries = new ArrayList<>();

    public TaskCacheBuilder(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    public <T> void add(TaskIO<T> io) {
        add(io, Function.identity());
    }

    public <T, U> void add(TaskIO<T> io, Function<? super T, ? extends U> mapper) {
        add(mapper.apply(io.get()));
    }

    public void add(Path file) {
        entries.add(e -> Hashing.tryAddFileBytes(e, file));
    }

    public void add(CharSequence str) {
        entries.add(e -> Hashing.addUTFBytes(e, str.toString()));
    }

    public void add(Number num) {
        entries.add(e -> Hashing.addUTFBytes(e, num.toString()));
    }

    public void add(Object obj) {
        switch (obj) {
            case Path path -> add(path);
            case CharSequence str -> add(str);
            case Number num -> add(num);
            case Optional<?> opt -> opt.ifPresent(this::add);
            case TaskIO<?> io -> add(io);
            default -> throw new IllegalStateException("Unable to cache value: " + obj.getClass());
        }
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
