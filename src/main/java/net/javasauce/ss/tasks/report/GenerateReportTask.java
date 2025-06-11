package net.javasauce.ss.tasks.report;

import com.google.gson.Gson;
import net.covers1624.quack.collection.ColUtils;
import net.covers1624.quack.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Created by covers1624 on 6/11/25.
 */
public class GenerateReportTask {

    private static final Gson GSON = new Gson();

    public static String generateReport(Map<String, TestCaseDef> defs) {
        if (defs.isEmpty()) return "No test stats found.";

        List<String> table = new ArrayList<>();
        List<TestCaseState> order = new ArrayList<>(Arrays.asList(TestCaseState.values())).reversed();
        table.add("<table>");
        table.add("<tr>");
        table.add("<td>Version</td>");
        for (var state : order) {
            emitTableCell(table, state.humanName);
        }
        table.add("</tr>");

        defs.forEach((id, def) -> {
            var stats = buildStats(def);
            table.add("<tr>");
            emitTableCell(table, id);
            for (var state : order) {
                emitTableCell(table, "" + stats.numCases[state.ordinal()]);
            }
            table.add("</tr>");
        });
        table.add("</table>");
        return String.join("\n", table);
    }

    private static void emitTableCell(List<String> table, String cellContent) {
        emitTableCell(table, List.of(cellContent));
    }

    // GitHub markdown is disgusting and requires lots of newlines inside a cell for markdown content (code blocks)
    private static void emitTableCell(List<String> table, List<String> cellContent) {
        if (ColUtils.allMatch(cellContent, String::isEmpty)) {
            table.add("<td></td>");
        } else {
            table.add("<td>");
            table.add("");
            table.addAll(cellContent);
            table.add("");
            table.add("</td>");
        }
    }

    public static @Nullable TestCaseDef loadTestStats(Path projectDir) {
        Path testStats = projectDir.resolve("src/main/resources/test_stats.json");
        if (Files.notExists(testStats)) {
            return null;
        }
        try {
            TestCaseDef defs = JsonUtils.parse(GSON, testStats, TestCaseDef.class);
            for (TestCaseDef.Case c : defs.cases.values()) {
                // TODO remove c.target == null, once testframework handles the source visitor crashing.
                if (c.broken != null || c.target == null) {
                    c.target = TestCaseState.BROKEN;
                }
            }
            return defs;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Stats buildStats(TestCaseDef def) {
        int[] numCases = new int[4];
        for (var c : def.cases.entrySet()) {
            numCases[c.getValue().target.ordinal()]++;
        }
        return new Stats(numCases);
    }

    private record Stats(
            int[] numCases
    ) { }
}
