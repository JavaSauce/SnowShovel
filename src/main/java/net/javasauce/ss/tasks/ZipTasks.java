package net.javasauce.ss.tasks;

import net.covers1624.quack.io.IOUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Created by covers1624 on 6/10/25.
 */
public class ZipTasks {

    public static void extractZip(Path zip, Path outputDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry ent;
            while ((ent = zin.getNextEntry()) != null) {
                if (ent.isDirectory()) continue;

                Files.copy(zin, IOUtils.makeParents(outputDir.resolve(ent.getName())), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
