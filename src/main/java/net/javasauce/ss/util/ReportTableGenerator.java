package net.javasauce.ss.util;

import net.covers1624.quack.collection.ColUtils;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.tasks.report.TestCaseState;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 8/18/25.
 */
public class ReportTableGenerator {

    private static final List<TestCaseState> ORDER = TestCaseState.VALUES.reversed();
    private final List<String> table = new ArrayList<>();

    public ReportTableGenerator() {
        table.add("<table>");
        table.add("<tr>");
        table.add("<td>Version</td>");
        for (var state : ORDER) {
            emitTableCell(table, state.humanName);
        }
        table.add("</tr>");
    }

    public ReportTableGenerator addRow(String id, TestCaseDef def, String repo, String branch) {
        if (table.isEmpty()) throw new RuntimeException("Already finished building.");

        var stats = sumCases(def);
        table.add("<tr>");
        emitTableCell(table, "[" + id + "](" + repo + "/tree/" + branch + ")");
        for (var state : ORDER) {
            emitTableCell(table, "" + stats[state.ordinal()]);
        }
        table.add("</tr>");
        return this;
    }

    public String build() {
        if (table.isEmpty()) throw new RuntimeException("Already finished building.");

        table.add("</table>");
        String builtTable = String.join("\n", table);
        table.clear();
        return builtTable;
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
            if (ColUtils.anyMatch(cellContent, e -> e.contains("`") || (e.contains("[") && e.contains("]")))) {
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
