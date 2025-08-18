package net.javasauce.ss.tasks.report;

import net.covers1624.quack.collection.FastStream;
import net.covers1624.quack.net.httpapi.HttpEngine;
import net.javasauce.ss.tasks.report.GenerateComparisonsTask.CaseComparison;
import net.javasauce.ss.tasks.report.GenerateComparisonsTask.ComparisonType;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.DiscordWebhook;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Created by covers1624 on 6/16/25.
 */
public class DiscordReportTask extends Task {

    private static final String GS = "\uD83D\uDFE9";
    private static final String RS = "\uD83D\uDFE5";

    public final TaskInput<String> webhook = input("webhook");
    public final TaskInput<String> gitRepoUrl = input("repoUrl");
    public final TaskInput<HttpEngine> http = input("http");
    public final TaskInput<List<String>> versions = input("versions");
    public final TaskInput<Map<String, CommittedTestCaseDef>> postDefs = input("postDefs");
    public final TaskInput<Map<String, CaseComparison>> comparisons = input("comparisons");

    private DiscordReportTask(String name, Executor executor) {
        super(name, executor);
    }

    public static DiscordReportTask create(String name, Executor executor, Consumer<DiscordReportTask> cons) {
        var task = new DiscordReportTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var webhook = this.webhook.get();
        var gitRepoUrl = this.gitRepoUrl.get();
        var http = this.http.get();
        var versions = this.versions.get();
        var postDefs = this.postDefs.get();
        var comparisons = this.comparisons.get();

        List<DiscordWebhook.Embed> embeds = new ArrayList<>();
        for (String id : versions.reversed()) {
            var comparison = comparisons.get(id);

            if (comparison == null) continue; // Uhh, not possible, but okay?

            // Ignore these.
            if (comparison.type() == ComparisonType.REMOVED) continue;

            var def = requireNonNull(postDefs.get(id), "CommittedTestCaseDef was expected to exist for id " + id);

            DiscordWebhook.Embed embed;
            if (comparison.type() == ComparisonType.ADDED) {
                embed = buildNewEmbed(gitRepoUrl, id, comparison, def.commitTitle());
            } else {
                embed = buildComparisonEmbed(gitRepoUrl, id, comparison, def.commitTitle());
            }
            if (!embed.hasFields()) {
                continue;
            }
            embeds.add(embed);
        }

        postEmbeds(webhook, http, embeds);

    }

    private static void postEmbeds(String webhook, HttpEngine http, List<DiscordWebhook.Embed> embeds) throws IOException {
        for (FastStream<DiscordWebhook.Embed> bucket : FastStream.of(embeds).partition(10)) {
            new DiscordWebhook(webhook)
                    .addEmbeds(bucket.toList())
                    .execute(http);
            // TODO obey discord rate limits, for now, just honk shoe for a bit.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static DiscordWebhook.Embed buildNewEmbed(String repoUrl, String mcVersion, CaseComparison comparison, String commitTitle) {
        var embed = new DiscordWebhook.Embed()
                .setTitle("New version: " + mcVersion)
                .setUrl(repoUrl + "/commits/" + comparison.rightCommit())
                .setDescription(commitTitle)
                .setColor(new Color(0x4CAF50));
        for (TestCaseState state : TestCaseState.VALUES.reversed()) {
            embed.addField(state.humanName, String.valueOf(comparison.numCases()[state.ordinal()]), false);
        }
        return embed;
    }

    private static DiscordWebhook.Embed buildComparisonEmbed(String repoUrl, String mcVersion, CaseComparison comp, String commitTitle) {
        var embed = new DiscordWebhook.Embed()
                .setTitle("Version changed: " + mcVersion)
                .setUrl(repoUrl + "/commits/" + comp.rightCommit())
                .setDescription(commitTitle)
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

            embed.addField(state.humanName + ": " + comp.numCases()[i] + " " + nameSuffix, summary, false);
        }
        return embed;
    }
}
