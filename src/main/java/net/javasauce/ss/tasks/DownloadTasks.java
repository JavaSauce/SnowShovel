package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.DownloadAction;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
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
public class DownloadTasks {

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
