package net.javasauce.ss.tasks.report;

import com.google.gson.Gson;
import net.covers1624.quack.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copied from test framework.
 * <p>
 * Created by covers1624 on 8/1/23.
 */
public class TestCaseDef {

    private static final Gson GSON = new Gson();

    public Map<String, Case> cases = new LinkedHashMap<>();

    public static TestCaseDef loadTestStats(Path testStats) {
        try (InputStream is = Files.newInputStream(testStats)) {
            return loadTestStats(is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static TestCaseDef loadTestStats(@WillNotClose InputStream is) throws IOException {
        TestCaseDef defs = JsonUtils.parse(GSON, is, TestCaseDef.class);
        for (TestCaseDef.Case c : defs.cases.values()) {
            if (c.broken != null) {
                c.target = TestCaseState.BROKEN;
            }
        }
        return defs;
    }

    public static class Case {

        @Nullable
        public TestCaseState target;
        @Nullable
        public BrokenDef broken;
    }

    public static class BrokenDef {

        @Nullable
        public String exception;
        public boolean ignoreMessage = false;
        @Nullable
        public String message;
    }
}
