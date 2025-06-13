package net.javasauce.ss.tasks;

import net.covers1624.quack.maven.MavenNotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by covers1624 on 1/20/25.
 */
public class ToolTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolTasks.class);

    public static Path extractTool(Path toolsDir, MavenNotation notation, Path toolZip) {
        return extractTool(toolsDir, notation, toolZip, true);
    }

    public static Path extractTool(Path toolsDir, MavenNotation notation, Path toolZip, boolean cacheExtract) {
        try {
            LOGGER.info("Extracting tool {}", notation);
            Path extractedDir = toolsDir
                    .resolve(notation.toModulePath())
                    .resolve(notation.version)
                    .resolve(notation.module + "-" + notation.version);
            if (Files.notExists(extractedDir) || !cacheExtract) {
                ZipTasks.extractZip(toolZip, extractedDir);
                LOGGER.info("Tool extracted.");
            } else {
                // TODO maybe validate somehow?
                LOGGER.info("Output exists, Skipped.");
            }
            return extractedDir.resolve(notation.withExtension("jar").toFileName());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to extract tool.", ex);
        }
    }
}
