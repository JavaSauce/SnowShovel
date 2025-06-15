package net.javasauce.ss.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by covers1624 on 6/16/25.
 */
public class JsonPretty {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static void prettyPrintJsonFile(Path file) throws IOException {
        Files.writeString(
                file,
                prettyPrint(Files.readString(file, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        );
    }

    public static String prettyPrint(String string) {
        return GSON.toJson(JsonParser.parseString(string));
    }
}
