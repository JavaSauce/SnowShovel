package net.javasauce.ss.tasks.report;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.CommittedTestCasePair;
import net.javasauce.ss.util.RunRequest;
import net.javasauce.ss.util.VersionRequest;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/11/25.
 */
public class GenerateComparisonsTask extends Task {

    public final TaskInput<Map<String, CommittedTestCasePair>> stats = input("stats");
    public final TaskInput<RunRequest> runRequest = input("runRequest");

    public final TaskOutput<Map<String, CaseComparison>> comparisons = computedOutput("comparisons");

    public GenerateComparisonsTask(String name, Executor executor) {
        super(name, executor);
    }

    public static GenerateComparisonsTask create(String name, Executor executor, Consumer<GenerateComparisonsTask> cons) {
        var task = new GenerateComparisonsTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var stats = this.stats.get();
        var runRequest = this.runRequest.get();
        var ranVersions = FastStream.of(runRequest.versions())
                .map(VersionRequest::id)
                .toList();

        var added = FastStream.of(ranVersions).filterNot(stats.keySet()::contains);
        var common = FastStream.of(ranVersions).filter(stats.keySet()::contains);
        var removed = FastStream.of(ranVersions).filterNot(stats.keySet()::contains);

        Map<String, CaseComparison> comparisons = new HashMap<>();
        added.forEach(id -> comparisons.put(id, compareCases(null, stats.get(id).now())));
        removed.forEach(id -> comparisons.put(id, compareCases(stats.get(id).before(), null)));
        common.forEach(id -> comparisons.put(id, compareCases(stats.get(id).before(), stats.get(id).now())));
        this.comparisons.set(comparisons);
    }

    // Mostly copied from CIBot, perhaps this should live in the test framework or something.
    public static CaseComparison compareCases(@Nullable CommittedTestCaseDef left, @Nullable CommittedTestCaseDef right) {
        if (left == null && right == null) throw new IllegalArgumentException("Left and Right can't be null.");

        if (left == null) return CaseComparison.added(sumCases(right.def()), right.commit());
        if (right == null) return CaseComparison.removed(sumCases(left.def()), left.commit());

        Map<String, TestCaseState> a = FastStream.of(left.def().cases.entrySet()).toMap(Map.Entry::getKey, e -> e.getValue().target);
        Map<String, TestCaseState> b = FastStream.of(right.def().cases.entrySet()).toMap(Map.Entry::getKey, e -> e.getValue().target);
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

        return new CaseComparison(
                ComparisonType.COMPARE,
                left.commit(),
                right.commit(),
                numCases,
                addedTotal,
                removedTotal,
                improvedStats,
                regressedStats
        );
    }

    private static int[] sumCases(TestCaseDef def) {
        int[] numCases = new int[4];
        for (var c : def.cases.entrySet()) {
            numCases[c.getValue().target.ordinal()]++;
        }
        return numCases;
    }

    public enum ComparisonType {
        COMPARE,
        ADDED,
        REMOVED,
    }

    public record CaseComparison(
            ComparisonType type,
            @Nullable String leftCommit,  // REMOVED or COMPARE
            @Nullable String rightCommit, // ADDED   or COMPARE
            int[] numCases,               // ANY
            int[] addedTotal,             // ADDED   or COMPARE
            int[] removedTotal,           // REMOVED or COMPARE
            int[] improvedStats,          // COMPARE
            int[] regressedStats          // COMPARE
    ) {

        public static CaseComparison added(int[] sum, String commit) {
            return new CaseComparison(
                    ComparisonType.ADDED,
                    null,
                    commit,
                    sum,
                    sum,
                    new int[0],
                    new int[0],
                    new int[0]
            );
        }

        public static CaseComparison removed(int[] sum, String commit) {
            return new CaseComparison(
                    ComparisonType.REMOVED,
                    commit,
                    null,
                    sum,
                    new int[0],
                    sum,
                    new int[0],
                    new int[0]
            );
        }
    }
}
