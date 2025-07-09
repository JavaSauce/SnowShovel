package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.net.HttpEngineDownloadAction;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.covers1624.quack.util.SneakyUtils.ThrowingConsumer;
import net.javasauce.ss.util.Hashing;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/24/25.
 */
public class NewDownloadTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewDownloadTask.class);

    private final HttpEngine http;

    public final TaskOutput<Path> output = output("output");
    public final TaskInput<String> url = input("url");

    public final TaskInput<Optional<Path>> localOverride = optionalInput("localOverride");

    public final TaskInput<Optional<String>> downloadHash = optionalInput("downloadHash");
    public final TaskInput<Long> downloadLen = input("downloadLen", -1L);

    private final List<ThrowingConsumer<Path, IOException>> mutators = new ArrayList<>();

    private NewDownloadTask(String name, Executor executor, HttpEngine http) {
        super(name, executor);
        this.http = http;

        withCaching(output, "_dl", cache -> {
            cache.add(output);
            cache.add(url);
            cache.add(downloadHash);
            cache.add(downloadLen);
        });
    }

    public static NewDownloadTask create(String name, Executor executor, HttpEngine http, Consumer<NewDownloadTask> configure) {
        var task = new NewDownloadTask(name, executor, http);
        configure.accept(task);
        return task;
    }

    public NewDownloadTask addMutator(ThrowingConsumer<Path, IOException> mutFunc) {
        mutators.add(mutFunc);
        return this;
    }

    protected void execute() throws IOException {
        var output = this.output.get();
        var url = this.url.get();
        var downloadHash = this.downloadHash.get().orElse(null);
        long downloadLen = this.downloadLen.get();
        var override = localOverride.get().orElse(null);

        if (override != null) {
            Files.copy(override, IOUtils.makeParents(output), StandardCopyOption.REPLACE_EXISTING);
            if (validate(output, downloadLen, downloadHash)) {
                LOGGER.info("Using local override for download of {}", output);
                return;
            }
        }

        LOGGER.info("Downloading file {} to {}", output, url);

        doDownload(http, url, output, downloadLen, downloadHash);

        for (ThrowingConsumer<Path, IOException> mutator : mutators) {
            mutator.accept(output);
        }
    }

    private static void doDownload(HttpEngine http, String url, Path output, long downloadLen, @Nullable String downloadHash) throws IOException {
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
}
