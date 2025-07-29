package net.javasauce.ss.util;

import com.google.gson.Gson;
import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.gson.JsonUtils;
import net.javasauce.ss.util.matrix.JobMatrix;
import net.javasauce.ss.util.matrix.MatrixJob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Created by covers1624 on 7/22/25.
 */
public record RunRequest(
        String reason,
        String decompilerVersion,
        List<VersionRequest> versions
) {

    private static final Gson GSON = new Gson();

    public static RunRequest parse(Path file) throws IOException {
        return JsonUtils.parse(GSON, file, RunRequest.class, StandardCharsets.UTF_8);
    }

    public static JobMatrix splitJobs(RunRequest runRequest, int jobCount) {
        var jobs = FastStream.of(runRequest.versions())
                .partition(jobCount)
                .map(e -> new RunRequest(
                        runRequest.reason(),
                        runRequest.decompilerVersion(),
                        e.toList()
                ))
                .map(e -> new MatrixJob("job", e))
                .toList();
        return new JobMatrix(jobs);
    }

    public static RunRequest mergeJobs(JobMatrix matrix) {
        var first = matrix.jobs().getFirst().parseRequest();
        return new RunRequest(
                first.reason,
                first.decompilerVersion,
                FastStream.of(matrix.jobs())
                        .map(MatrixJob::parseRequest)
                        .flatMap(e -> e.versions)
                        .toList()
        );
    }
}
