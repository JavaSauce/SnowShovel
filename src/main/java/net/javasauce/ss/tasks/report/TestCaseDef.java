package net.javasauce.ss.tasks.report;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copied from test framework.
 * <p>
 * Created by covers1624 on 8/1/23.
 */
public class TestCaseDef {

    public Map<String, Case> cases = new LinkedHashMap<>();

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
