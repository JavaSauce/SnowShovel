package net.javasauce.ss.util.matrix;

import com.google.gson.Gson;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by covers1624 on 7/22/25.
 */
public record JobMatrix(List<MatrixJob> jobs) {

    private static final Gson GSON = new Gson();

    public static JobMatrix parse(Path file) throws IOException {
        return JsonUtils.parse(GSON, file, JobMatrix.class, StandardCharsets.UTF_8);
    }

    public static void write(Path file, JobMatrix matrix) throws IOException {
        JsonUtils.write(GSON, IOUtils.makeParents(file), matrix, StandardCharsets.UTF_8);
    }
}
