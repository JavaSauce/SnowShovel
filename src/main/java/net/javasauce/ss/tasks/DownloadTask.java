package net.javasauce.ss.tasks;

import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.covers1624.quack.util.SneakyUtils;
import net.covers1624.quack.util.SneakyUtils.ThrowingConsumer;
import net.javasauce.ss.util.Hashing;
import net.javasauce.ss.util.TaskCache;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Created by covers1624 on 6/24/25.
 */
public class DownloadTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadTask.class);

    private final Path output;
    private final String url;

    private @Nullable Path localOverride;

    private @Nullable String downloadHash;
    private long downloadLen = -1;

    private final List<ThrowingConsumer<Path, IOException>> mutators = new ArrayList<>();

    private boolean executed;

    private DownloadTask(Path output, String url) {
        this.output = output;
        this.url = url;
    }

    public static DownloadTask of(Path output, String url) {
        return new DownloadTask(output, url);
    }

    public static DownloadTask forMaven(Path destDir, String repo, MavenNotation notation) {
        return DownloadTask.of(notation.toPath(destDir), notation.toURL(repo).toString())
                .withLocalOverride(findMavenLocalFile(notation));
    }

    public DownloadTask withLocalOverride(@Nullable Path file) {
        localOverride = file;
        return this;
    }

    public DownloadTask withDownloadHash(@Nullable String hash) {
        downloadHash = hash;
        return this;
    }

    public DownloadTask withDownloadLen(long len) {
        downloadLen = len;
        return this;
    }

    public DownloadTask withMutator(ThrowingConsumer<Path, IOException> mutFunc) {
        mutators.add(mutFunc);
        return this;
    }

    public CompletableFuture<Path> executeAsync(HttpEngine http) {
        return executeAsync(http, ForkJoinPool.commonPool());
    }

    public CompletableFuture<Path> executeAsync(HttpEngine http, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(http);
            } catch (IOException ex) {
                SneakyUtils.throwUnchecked(ex);
                return null; // Never hit.
            }
        }, executor);
    }

    public Path execute(HttpEngine http) throws IOException {
        if (executed) throw new RuntimeException("Already executed.");

        TaskCache cache = TaskCache.forOutput(output, "_dl")
                .add(output)
                .add(url)
                .addNullable(downloadHash)
                .add(downloadLen);

        if (cache.isUpToDate()) {
            LOGGER.info("Skipped download of {}, cache hit.", output);
            return output;
        }

        if (localOverride != null) {
            Files.copy(localOverride, output, StandardCopyOption.REPLACE_EXISTING);
            if (validate(output, downloadLen, downloadHash)) {
                LOGGER.info("Using local override for download of {}", output);
                return output;
            }
        }

        LOGGER.info("Downloading file {} to {}", output, url);

        doDownload(http);

        for (ThrowingConsumer<Path, IOException> mutator : mutators) {
            mutator.accept(output);
        }
        cache.writeCache();
        executed = true;
        return output;
    }

    private void doDownload(HttpEngine http) throws IOException {
        IOException exception = null;
        for (int i = 0; i < 10; i++) {
            try {
                new HttpEngineDownloadAction(http)
                        .setUrl(url)
                        .setDest(output)
                        .setQuiet(false)
                        .execute();

                if (!validate(output, downloadLen, downloadHash)) {
                    LOGGER.error("Download validations failed. File will be re-downloaded.");
                    continue;
                }
                exception = null;
                break;
            } catch (IOException ex) {
                if (exception == null) {
                    exception = ex;
                } else {
                    exception.addSuppressed(ex);
                }
            }
        }
        if (exception != null) {
            throw exception;
        }
    }

    private static boolean validate(Path file, long length, @Nullable String sha1) throws IOException {
        if (length != -1) {
            if (Files.size(file) != length) return false;
        }

        if (sha1 != null) {
            return Hashing.hashFile(Hashing.SHA1, file).equals(sha1);
        }

        return true;
    }

    private static @Nullable Path findMavenLocalFile(MavenNotation notation) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) return null;

        Path mavenLocalFile = notation.toPath(Path.of(userHome).resolve(".m2/repository"));
        if (!Files.exists(mavenLocalFile)) return null;

        return mavenLocalFile;
    }
}
