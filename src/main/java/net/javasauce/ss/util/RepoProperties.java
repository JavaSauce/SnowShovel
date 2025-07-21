package net.javasauce.ss.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by covers1624 on 7/20/25.
 */
public class RepoProperties {

    public static final String TAG_SNOW_SHOVEL_VERSION = "SnowShovelVersion";
    public static final String TAG_DECOMPILER_VERSION = "DecompilerVersion";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private final Path cacheDir;

    private final Map<String, String> properties;

    public RepoProperties(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;

        var versionsFile = cacheDir.resolve("versions.json");
        Map<String, String> properties = null;
        if (Files.exists(versionsFile)) {
            properties = JsonUtils.parse(GSON, versionsFile, MAP_STRING_TYPE, StandardCharsets.UTF_8);
        }
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
        this.properties = properties;
    }

    public @Nullable String getValue(String name) {
        return properties.get(name);
    }

    public void setValue(String name, String value) {
        properties.put(name, value);
    }

    public void save() throws IOException {
        var versionsFile = cacheDir.resolve("versions.json");
        JsonUtils.write(GSON, IOUtils.makeParents(versionsFile), properties, MAP_STRING_TYPE, StandardCharsets.UTF_8);
    }

}
