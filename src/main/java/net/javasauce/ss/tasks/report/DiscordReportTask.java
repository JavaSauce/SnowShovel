package net.javasauce.ss.tasks.report;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.tasks.VersionManifestTasks;
import net.javasauce.ss.util.DiscordWebhook;
import net.javasauce.ss.util.VersionListManifest;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 6/16/25.
 */
public class DiscordReportTask {

    private static final String GS = "\uD83D\uDFE9";
    private static final String RS = "\uD83D\uDFE5";

    public static void generateReports(SnowShovel ss, String webhook, Map<String, TestCaseDef> preTestStats, Map<String, TestCaseDef> postTestStats) throws IOException {
        var order = FastStream.of(VersionManifestTasks.allVersions(ss))
                .map(VersionListManifest.Version::id)
                .toList();

        List<List<DiscordWebhook.Embed>> embeds = new ArrayList<>();
        List<DiscordWebhook.Embed> building = new ArrayList<>();
        for (String id : order.reversed()) {
            TestCaseDef pre = preTestStats.get(id);
            TestCaseDef post = postTestStats.get(id);

            if (post == null) continue; // We don't currently care about removals.

            if (pre == null) {
                building.add(buildNewEmbed(id, post));
            } else {
                building.add(buildComparisonEmbed(id, pre, post));
            }
            if (building.size() == 10) {
                embeds.add(List.copyOf(building));
                building.clear();
            }
        }
        if (!building.isEmpty()) {
            embeds.add(List.copyOf(building));
        }

        for (var values : embeds) {
            new DiscordWebhook(webhook)
                    .addEmbeds(values)
                    .execute(ss.http);
            // TODO obey discord rate limits, for now, just honk shoe for a bit.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static DiscordWebhook.@NotNull Embed buildNewEmbed(String mcVersion, TestCaseDef def) {
        var stats = GenerateReportTask.buildStats(def);
        var embed = new DiscordWebhook.Embed()
                .setTitle("New version: " + mcVersion)
                .setColor(new Color(0x4CAF50));
        for (TestCaseState state : TestCaseState.VALUES.reversed()) {
            embed.addField(state.humanName, String.valueOf(stats.numCases()[state.ordinal()]), false);
        }
        return embed;
    }

    private static DiscordWebhook.Embed buildComparisonEmbed(String mcVersion, TestCaseDef left, TestCaseDef right) {
        var comp = GenerateReportTask.compareCases(left, right);

        var embed = new DiscordWebhook.Embed()
                .setTitle("Version changed: " + mcVersion)
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
}
