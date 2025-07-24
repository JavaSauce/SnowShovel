package net.javasauce.ss.util;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.covers1624.quack.util.SneakyUtils;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by covers1624 on 1/20/25.
 */
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

    public static InMemoryDownload doDownload(HttpEngine http, String url, @Nullable InMemoryDownload existing) {
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
        boolean isUpToDate = getWithRetry(10, () -> {
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

    private static <T> T getWithRetry(int retries, SneakyUtils.ThrowingSupplier<T, IOException> r) {
        if (retries == 0) throw new IllegalArgumentException("Need more than 0 retries.");

        Throwable throwable = null;
        for (int i = 0; i < retries; i++) {
            try {
                return r.get();
            } catch (Throwable ex) {
                if (throwable == null) {
                    throwable = ex;
                } else {
                    throwable.addSuppressed(ex);
                }
            }
        }
        if (throwable == null) {
            throwable = new RuntimeException("Run failed?");
        }
        SneakyUtils.throwUnchecked(throwable);
        return null; // Never hit.
    }

    @Override
    public String toString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
