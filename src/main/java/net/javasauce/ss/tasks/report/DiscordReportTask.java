package net.javasauce.ss.tasks.report;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.SnowShovel;
import net.javasauce.ss.tasks.VersionManifestTasks;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.DiscordWebhook;
import net.javasauce.ss.util.VersionListManifest;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by covers1624 on 6/16/25.
 */
public class DiscordReportTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordReportTask.class);

    private static final String GS = "\uD83D\uDFE9";
    private static final String RS = "\uD83D\uDFE5";

    public static void generateReports(SnowShovel ss, String webhook, Map<String, CommittedTestCaseDef> preTestStats, Map<String, CommittedTestCaseDef> postTestStats, Map<String, String> commitTitles) throws IOException {
        LOGGER.info("Building Discord reports.");
        var order = FastStream.of(VersionManifestTasks.allVersions(ss))
                .map(VersionListManifest.Version::id)
                .toList();

        List<List<DiscordWebhook.Embed>> embeds = new ArrayList<>();
        List<DiscordWebhook.Embed> building = new ArrayList<>();
        for (String id : order.reversed()) {
            CommittedTestCaseDef pre = preTestStats.get(id);
            CommittedTestCaseDef post = postTestStats.get(id);

            if (post == null) continue; // We don't currently care about removals.

            if (pre == null) {
                building.add(buildNewEmbed(ss, id, post, commitTitles.get(id)));
            } else {
                building.add(buildComparisonEmbed(ss, id, pre, post, commitTitles.get(id)));
            }
            if (building.size() == 10) {
                embeds.add(List.copyOf(building));
                building.clear();
            }
        }
        if (!building.isEmpty()) {
            embeds.add(List.copyOf(building));
        }

        LOGGER.info("Posting to discord.");
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

    private static DiscordWebhook.Embed buildNewEmbed(SnowShovel ss, String mcVersion, CommittedTestCaseDef def, @Nullable String commitTitle) {
        var stats = GenerateReportTask.buildStats(def.def());
        var embed = new DiscordWebhook.Embed()
                .setTitle("New version: " + mcVersion)
                .setUrl(ss.gitRepo + "/commits/" + def.commit())
                .setDescription(String.valueOf(commitTitle))
                .setColor(new Color(0x4CAF50));
        for (TestCaseState state : TestCaseState.VALUES.reversed()) {
            embed.addField(state.humanName, String.valueOf(stats.numCases()[state.ordinal()]), false);
        }
        return embed;
    }

    private static DiscordWebhook.Embed buildComparisonEmbed(SnowShovel ss, String mcVersion, CommittedTestCaseDef left, CommittedTestCaseDef right, @Nullable String commitTitle) {
        var comp = GenerateReportTask.compareCases(left.def(), right.def());

        var embed = new DiscordWebhook.Embed()
                .setTitle("Version changed: " + mcVersion)
                .setUrl(ss.gitRepo + "/compare/" + left.commit() + "..." + right.commit())
                .setDescription(String.valueOf(commitTitle))
                .setColor(new Color(0xFF9800));
        for (TestCaseState state : TestCaseState.VALUES.reversed()) {
            int i = state.ordinal();
            if (comp.addedTotal()[i] == 0 && comp.removedTotal()[i] == 0) continue;
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
