package net.javasauce.ss.util;

import net.covers1624.quack.maven.MavenNotation;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.tasks.DownloadTasks;
import net.javasauce.ss.tasks.ToolTasks;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 6/14/25.
 */
public final class ToolProvider {

    private final SnowShovel ss;
    private final MavenNotation baseNotation;

    private @Nullable MavenNotation notation;
    private @Nullable CompletableFuture<Path> toolFuture;

    private boolean requiresExtracting;

    public ToolProvider(SnowShovel ss, MavenNotation baseNotation) {
        this.ss = ss;
        this.baseNotation = baseNotation;
    }

    public ToolProvider enableExtraction() {
        if (toolFuture != null) throw new IllegalStateException("Already resolved.");
        requiresExtracting = true;
        return this;
    }

    public void resolveWithVersion(String version) {
        if (toolFuture != null) throw new IllegalStateException("Already resolved.");

        notation = baseNotation.withVersion(version);
        toolFuture = DownloadTasks.downloadMavenArtifactAsync(ss.http, "https://maven.covers1624.net", notation, ss.toolsDir);
        if (requiresExtracting) {
            toolFuture = toolFuture.thenApplyAsync(e -> ToolTasks.extractTool(ss.toolsDir, notation, e, !version.contains("SNAPSHOT")));
        }
    }

    public MavenNotation notation() {
        if (notation == null) throw new IllegalStateException("Not resolved yet.");

        return notation;
    }

    public Path getTool() {
        if (toolFuture == null) throw new IllegalStateException("Not resolved yet.");

        return toolFuture.join();
    }

}
