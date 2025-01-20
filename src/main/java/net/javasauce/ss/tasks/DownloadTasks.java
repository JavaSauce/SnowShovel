package net.javasauce.ss.tasks;

import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.util.Hashing;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 1/20/25.
 */
public class DownloadTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadTasks.class);

    public static CompletableFuture<Path> downloadFileAsync(HttpEngine http, String url, Path dest) {
        return downloadFileAsync(http, url, dest, -1, null);
    }

    public static CompletableFuture<Path> downloadFileAsync(HttpEngine http, String url, Path dest, long length, @Nullable String sha1) {
        return CompletableFuture.supplyAsync(() -> downloadFile(http, url, dest, length, sha1));
    }

    public static Path downloadFile(HttpEngine http, String url, Path dest) {
        return downloadFile(http, url, dest, -1, null);
    }

    public static Path downloadFile(HttpEngine http, String url, Path dest, long length, @Nullable String sha1) {
        try {
            if (Files.exists(dest)) {
                if (validate(dest, length, sha1)) {
                    return dest;
                }
                LOGGER.warn("Download {} failed hash check. Will be re-downloaded.", dest);
            }

            doDownload(http, url, dest);

            if (!validate(dest, length, sha1)) {
                throw new RuntimeException("Failed to validate file after download.");
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to download file.", ex);
        }
        return dest;
    }

    private static boolean validate(Path file, long length, @Nullable String sha1) throws IOException {
        if (length != -1) {
            if (Files.size(file) != length) return false;
        }

        if (sha1 != null) {
            return Hashing.hashFile("SHA-1", file).equals(sha1);
        }

        return true;
    }

    private static void doDownload(HttpEngine http, String url, Path dest) throws IOException {
        new HttpEngineDownloadAction(http)
                .setUrl(url)
                .setDest(dest)
                .setQuiet(false)
                .execute();
    }
}
