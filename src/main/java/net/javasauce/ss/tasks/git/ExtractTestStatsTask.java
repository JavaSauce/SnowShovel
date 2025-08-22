package net.javasauce.ss.tasks.git;

import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.CommittedTestCasePair;
import net.javasauce.ss.util.ProcessableVersionSet;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.eclipse.jgit.lib.Ref;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/30/25.
 */
public class ExtractTestStatsTask extends AbstractGitTask {

    public final TaskInput<ProcessableVersionSet> versionSet = input("versionSet");

    public final TaskOutput<Map<String, CommittedTestCasePair>> testStats = computedOutput("testStats");

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
        var git = this.git.get();
        var repository = git.getRepository();
        var versionSet = this.versionSet.get();

        Map<String, CommittedTestCasePair> defs = new HashMap<>();
        for (String id : versionSet.allVersions()) {
            var manifest = versionSet.getManifest(id);
            var branchName = manifest.computeBranchName();
            Ref ref = repository.findRef(branchName);
            if (ref == null) continue;
            var nowCommit = ref.getObjectId().name();
            var beforeCommit = getParentCommit(nowCommit);

            defs.put(id, new CommittedTestCasePair(
                    id,
                    getTestDef(beforeCommit),
                    getTestDef(nowCommit)
            ));
        }
        testStats.set(defs);
    }

    private CommittedTestCaseDef getTestDef(String commit) throws IOException {
        var testDef = loadBlob(commit + ":src/main/resources/test_stats.json", TestCaseDef::loadTestStats);
        if (testDef == null) throw new RuntimeException("Missing test defs for commit " + commit);

        return new CommittedTestCaseDef(
                commit,
                getCommitMessage(commit),
                testDef
        );
    }
}
