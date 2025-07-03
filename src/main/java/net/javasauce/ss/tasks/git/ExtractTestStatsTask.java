package net.javasauce.ss.tasks.git;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/30/25.
 */
public class ExtractTestStatsTask extends AbstractGitTask {

    public final TaskInput<Boolean> checkOrigin = input("checkOrigin");

    public final TaskOutput<Map<String, CommittedTestCaseDef>> testStats = computedOutput("testStats");

    private ExtractTestStatsTask(String name, Executor executor) {
        super(name, executor);
    }

    public static ExtractTestStatsTask create(String name, Executor executor, Consumer<ExtractTestStatsTask> cons) {
        var task = new ExtractTestStatsTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var branches = listAllBranches();
        Map<String, String> idToCommit = new HashMap<>();
        if (checkOrigin.get()) {
            FastStream.of(branches.entrySet())
                    .filter(e -> e.getKey().startsWith("origin/"))
                    .forEach(e -> parseBranchToId(e.getKey().replace("origin/", ""), e.getValue(), idToCommit));
        }
        FastStream.of(branches.entrySet())
                .forEach(e -> parseBranchToId(e.getKey(), e.getValue(), idToCommit));
        Map<String, CommittedTestCaseDef> defs = new HashMap<>();
        for (var entry : idToCommit.entrySet()) {
            var testDefs = loadBlob(entry.getValue() + ":src/main/resources/test_stats.json", TestCaseDef::loadTestStats);
            if (testDefs == null) continue;

            defs.put(entry.getKey(), new CommittedTestCaseDef(entry.getValue(), testDefs));
        }

        testStats.set(defs);
    }

    private static void parseBranchToId(String branch, String commit, Map<String, String> idToCommit) {
        if (branch.startsWith("release/") || branch.startsWith("snapshot/")) {
            idToCommit.put(branch.replace("release/", "").replace("snapshot/", ""), commit);
        }
    }
}
