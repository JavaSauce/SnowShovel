package net.javasauce.ss.util.matrix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.javasauce.ss.util.RunRequest;

/**
 * Created by covers1624 on 7/22/25.
 */
public record MatrixJob(
        String name,
        String request
) {

    private static final Gson GSON = new GsonBuilder().create();

    public MatrixJob(String name, RunRequest request) {
        this(name, GSON.toJson(request));
    }

    public RunRequest parseRequest() {
        return GSON.fromJson(request(), RunRequest.class);
    }
}
