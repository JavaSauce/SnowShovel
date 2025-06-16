package net.javasauce.ss.tasks.report;

import com.google.gson.Gson;
import net.covers1624.quack.collection.ColUtils;
import net.covers1624.quack.collection.FastStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by covers1624 on 6/11/25.
 */
public class GenerateReportTask {

    private static final Gson GSON = new Gson();

    public static String generateReport(List<ReportPair> defs) {
        if (defs.isEmpty()) return "No test stats found.";

        List<String> table = new ArrayList<>();
        List<TestCaseState> order = TestCaseState.VALUES.reversed();
        table.add("<table>");
        table.add("<tr>");
        table.add("<td>Version</td>");
        for (var state : order) {
            emitTableCell(table, state.humanName);
        }
        table.add("</tr>");

        defs.forEach(e -> {
            var stats = buildStats(e.def);
            table.add("<tr>");
            emitTableCell(table, e.mcVersion);
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
            // If there is any Markdown code blocks/quotes in the cell, we need to emit full spaces before/after its lines, because ~~GitHub~~.
            if (ColUtils.anyMatch(cellContent, e -> e.contains("`"))) {
                table.add("<td>");
                table.add("");
                table.addAll(cellContent);
                table.add("");
                table.add("</td>");
            } else if (cellContent.size() == 1) {
                table.add("<td>" + cellContent.getFirst() + "</td>");
            } else {
                table.add("<td>");
                table.addAll(cellContent);
                table.add("</td>");
            }
        }
    }

    public static Stats buildStats(TestCaseDef def) {
        int[] numCases = new int[4];
        for (var c : def.cases.entrySet()) {
            numCases[c.getValue().target.ordinal()]++;
        }
        return new Stats(numCases);
    }

    // Mostly copied from CIBot, perhaps this should live in the test framework or something.
    public static CaseComparison compareCases(TestCaseDef left, TestCaseDef right) {
        Map<String, TestCaseState> a = FastStream.of(left.cases.entrySet()).toMap(Map.Entry::getKey, e -> e.getValue().target);
        Map<String, TestCaseState> b = FastStream.of(right.cases.entrySet()).toMap(Map.Entry::getKey, e -> e.getValue().target);
        int[] numCases = new int[4];
        for (var value : b.values()) {
            numCases[value.ordinal()]++;
        }

        int[] addedTotal = new int[4];
        int[] removedTotal = new int[4];

        int[] improvedStats = new int[4];
        int[] regressedStats = new int[4];

        FastStream.of(a.keySet())
                .filter(b.keySet()::contains)
                .forEach(name -> {
                    TestCaseState aState = a.get(name);
                    TestCaseState bState = b.get(name);
                    if (aState != bState) {
                        removedTotal[aState.ordinal()]++;
                        addedTotal[bState.ordinal()]++;
                        if (bState.ordinal() > aState.ordinal()) {
                            improvedStats[bState.ordinal()]++;
                        } else {
                            regressedStats[bState.ordinal()]++;
                        }
                    }
                });

        return new CaseComparison(numCases, addedTotal, removedTotal, improvedStats, regressedStats);
    }

    public record Stats(int[] numCases) { }

    public record CaseComparison(
            int[] numCases,
            int[] addedTotal,
            int[] removedTotal,
            int[] improvedStats,
            int[] regressedStats
    ) { }

    public record ReportPair(String mcVersion, TestCaseDef def) { }
}
