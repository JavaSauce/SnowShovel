package net.javasauce.ss.tasks.util;

import net.covers1624.quack.collection.ColUtils;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.tasks.report.TestCaseState;
import net.javasauce.ss.util.task.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Created by covers1624 on 7/16/25.
 */
public abstract class GenProjectTask extends Task {

    protected GenProjectTask(String name, Executor executor) {
        super(name, executor);
    }

    protected static String generateReport(Map<String, TestCaseDef> defs) {
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

        defs.forEach((id, def) -> {
            var stats = sumCases(def);
            table.add("<tr>");
            emitTableCell(table, id);
            for (var state : order) {
                emitTableCell(table, "" + stats[state.ordinal()]);
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

    private static int[] sumCases(TestCaseDef def) {
        int[] numCases = new int[4];
        for (var c : def.cases.entrySet()) {
            numCases[c.getValue().target.ordinal()]++;
        }
        return numCases;
    }
}
