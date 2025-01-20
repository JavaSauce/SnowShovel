package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Created by covers1624 on 1/20/25.
 */
public class ToolTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolTasks.class);

    public static Path extractTool(Path toolsDir, MavenNotation notation, Path toolZip) {
        try {
            LOGGER.info("Extracting tool {}", notation);
            Path extractedDir = toolsDir
                    .resolve(notation.toModulePath())
                    .resolve(notation.version)
                    .resolve(notation.module + "-" + notation.version);
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(toolZip))) {
                ZipEntry ent;
                while ((ent = zin.getNextEntry()) != null) {
                    if (ent.isDirectory()) continue;

                    Files.copy(zin, IOUtils.makeParents(extractedDir.resolve(ent.getName())), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            LOGGER.info("Tool extracted.");
            return extractedDir.resolve(notation.withExtension("jar").toFileName());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to extract tool.", ex);
        }
    }
}
