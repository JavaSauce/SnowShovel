package net.javasauce.ss.util;

import net.covers1624.quack.maven.MavenNotation;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.tasks.DownloadTask;
import net.javasauce.ss.tasks.DownloadTasks;
import net.javasauce.ss.tasks.ToolTasks;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Created by covers1624 on 6/14/25.
 */
public final class ToolProvider {

    private static final String MAVEN = "https://maven.covers1624.net/";

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
        toolFuture = DownloadTask.forMaven(ss.toolsDir, MAVEN, notation)
                .executeAsync(ss.http);
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

    public String findLatest() {
        var download = DownloadTasks.inMemoryDownload(ss.http, MAVEN + baseNotation.toModulePath() + "maven-metadata.xml", null);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document doc = builder.parse(new InputSource(new StringReader(download.toString())));
            doc.getDocumentElement().normalize();

            return doc.getElementsByTagName("latest")
                    .item(0)
                    .getTextContent();
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new RuntimeException("Failed to parse xml.", ex);
        }
    }

}
