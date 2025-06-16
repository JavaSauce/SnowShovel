package net.javasauce.ss.tasks.report;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.report.GenerateReportTask.ReportPair;
import net.javasauce.ss.util.DiscordWebhook;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 6/16/25.
 */
public class DiscordReportTask {

    private static final String GS = "\uD83D\uDFE9";
    private static final String RS = "\uD83D\uDFE5";

    public static void generateReports(HttpEngine http, String webhook, Map<String, TestCaseDef> preTestStats, Map<String, TestCaseDef> postTestStats) throws IOException {
        reportNewVersions(http, webhook, FastStream.of(postTestStats.entrySet())
                .filterNot(e -> preTestStats.containsKey(e.getKey()))
                .map(e -> new ReportPair(e.getKey(), e.getValue()))
                .toList()
        );

        var changedVersionEmbeds = FastStream.of(postTestStats.entrySet())
                .filter(e -> preTestStats.containsKey(e.getKey()))
                .map(e -> new ComparisonPair(e.getKey(), preTestStats.get(e.getKey()), e.getValue()))
                .map(DiscordReportTask::buildComparisonEmbed)
                .toList();

        for (var embed : changedVersionEmbeds) {
            // TODO obey discord rate limits.
            new DiscordWebhook(webhook)
                    .addEmbed(embed)
                    .execute(http);
        }
    }

    private static void reportNewVersions(HttpEngine http, String webhook, List<ReportPair> newVersions) throws IOException {
        for (ReportPair newVersion : newVersions) {
            var stats = GenerateReportTask.buildStats(newVersion.def());
            var embed = new DiscordWebhook.Embed()
                    .setTitle(newVersion.mcVersion())
                    .setColor(new Color(0x4CAF50));
            for (TestCaseState state : TestCaseState.VALUES.reversed()) {
                embed.addField(state.humanName, String.valueOf(stats.numCases()[state.ordinal()]), state != TestCaseState.BROKEN);
            }

            new DiscordWebhook(webhook)
                    .setContent("New Minecraft version!")
                    .addEmbed(embed)
                    .execute(http);
        }
    }

    private static DiscordWebhook.Embed buildComparisonEmbed(ComparisonPair version) {
        var comp = GenerateReportTask.compareCases(version.left, version.right);

        var embed = new DiscordWebhook.Embed()
                .setTitle("Version changed: " + version.mcVersion())
                .setColor(new Color(0xFF9800));
        for (TestCaseState state : TestCaseState.VALUES.reversed()) {
            int i = state.ordinal();
            if (comp.numCases()[i] == 0 && comp.removedTotal()[i] == 0) continue;
            String summary = "";
            if (comp.improvedStats()[i] > 0) {
                summary = GS + " " + comp.improvedStats()[i] + " improved";
            }
            if (comp.regressedStats()[i] > 0) {
                if (!summary.isEmpty()) summary += " ";
                summary += RS + " " + comp.regressedStats()[i] + " regressed";
            }

            String nameSuffix = comp.addedTotal()[i] != 0 || comp.removedTotal()[i] != 0 ? "(+" + comp.addedTotal()[i] + " -" + comp.removedTotal()[i] + ")" : "";

            embed.addField(state.humanName + " " + nameSuffix, summary, false);
        }
        return embed;
    }

    private record ComparisonPair(String mcVersion, TestCaseDef left, TestCaseDef right) { }
}
