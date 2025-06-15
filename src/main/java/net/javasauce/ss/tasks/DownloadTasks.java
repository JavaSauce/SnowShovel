package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.util.Hashing;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public static CompletableFuture<Path> downloadMavenArtifactAsync(HttpEngine http, String mavenRepo, MavenNotation notation, Path librariesDir) {
        return CompletableFuture.supplyAsync(() -> downloadMavenArtifact(http, mavenRepo, notation, librariesDir));
    }

    public static Path downloadMavenArtifact(HttpEngine http, String mavenRepo, MavenNotation notation, Path librariesDir) {
        LOGGER.info("Downloading maven artifact {}", notation);
        Path output = notation.toPath(librariesDir);
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path mavenLocalFile = notation.toPath(Path.of(userHome).resolve(".m2/repository"));
            if (Files.exists(mavenLocalFile)) {
                LOGGER.info(" Found compatible artifact in MavenLocal, using that.");
                try {
                    Files.copy(mavenLocalFile, IOUtils.makeParents(output), StandardCopyOption.REPLACE_EXISTING);
                    return output;
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to copy file from maven local.", ex);
                }
            }
        }

        return downloadFile(http, notation.toURL(mavenRepo).toString(), output);
    }

    public static Path downloadFile(HttpEngine http, String url, Path dest) {
        return downloadFile(http, url, dest, -1, null);
    }

    public static Path downloadFile(HttpEngine http, String url, Path dest, long length, @Nullable String sha1) {
        return Tasks.getWithRetry(10, () -> {
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
                return dest;
            } catch (IOException ex) {
                throw new RuntimeException("Failed to download file.", ex);
            }
        });
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

    private static void doDownload(HttpEngine http, String url, Path dest) throws IOException {
        new HttpEngineDownloadAction(http)
                .setUrl(url)
                .setDest(dest)
                .setQuiet(false)
                .execute();
    }

    public static InMemoryDownload inMemoryDownload(HttpEngine http, String url, @Nullable DownloadTasks.InMemoryDownload existing) {
        class DownloadDest implements DownloadAction.Dest {

            private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            private @Nullable String etag;

            public void reset() {
                bos.reset();
                etag = existing != null ? existing.etag : null;
            }

            // @formatter:off
            @Override public OutputStream getOutputStream() { return bos; }
            @Override public @Nullable String getEtag() { return etag; }
            @Override public void setEtag(String etag) { this.etag = etag; }
            @Override public long getLastModified() { return -1; }
            @Override public void setLastModified(long time) { }
            @Override public void onFinished(boolean success) { }
            // @formatter:on
        }
        DownloadDest dest = new DownloadDest();
        boolean isUpToDate = Tasks.getWithRetry(10, () -> {
                    dest.reset();
                    var action = new HttpEngineDownloadAction(http)
                            .setUrl(url)
                            .setDest(dest)
                            .setQuiet(false)
                            .setUseETag(true);
                    action.execute();
                    if (action.isUpToDate() && existing != null) {
                        dest.bos.writeBytes(existing.body);
                    }
                    return action.isUpToDate();
                }
        );
        return new InMemoryDownload(isUpToDate, dest.bos.toByteArray(), dest.etag);
    }

    public record InMemoryDownload(boolean isUpToDate, byte[] body, @Nullable String etag) {

        public static InMemoryDownload readFrom(Path file) throws IOException {
            Path etagFile = file.resolveSibling(file.getFileName() + ".etag");

            return new InMemoryDownload(
                    true, // Maybe false? meh?
                    Files.readAllBytes(file),
                    Files.exists(etagFile) ? Files.readString(etagFile, StandardCharsets.UTF_8) : null
            );
        }

        public void writeTo(Path file) throws IOException {
            Files.write(IOUtils.makeParents(file), body);
            if (etag != null) {
                Files.writeString(file.resolveSibling(file.getFileName() + ".etag"), etag, StandardCharsets.UTF_8);
            }
        }

        @Override
        public String toString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
