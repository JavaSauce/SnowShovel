package net.javasauce.ss.tasks.git;

import net.javasauce.ss.tasks.report.TestCaseDef;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.ProcessableVersionSet;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/30/25.
 */
public class ExtractTestStatsTask extends AbstractGitTask {

    public final TaskInput<Boolean> checkOrigin = input("checkOrigin");
    public final TaskInput<ProcessableVersionSet> versionSet = input("versionSet");

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
        var git = this.git.get();
        var repository = git.getRepository();
        var versionSet = this.versionSet.get();

        Map<String, CommittedTestCaseDef> defs = new HashMap<>();
        for (String id : versionSet.allVersions()) {
            var manifest = versionSet.getManifest(id);
            var branchName = manifest.computeBranchName();
            Ref ref = null;
            if (checkOrigin.get()) {
                ref = repository.findRef("origin/" + branchName);
            }
            if (ref == null) {
                ref = repository.findRef(branchName);
            }
            if (ref == null) continue;

            var objId = ref.getObjectId();
            if (objId == null) throw new RuntimeException("Ref has no object? " + branchName + " " + ref);

            var refName = Repository.shortenRefName(objId.getName());
            var testDef = loadBlob(refName + ":src/main/resources/test_stats.json", TestCaseDef::loadTestStats);
            if (testDef == null) throw new RuntimeException("Missing test defs for branch " + branchName);

            defs.put(id, new CommittedTestCaseDef(refName, getCommitMessage(refName), testDef));
        }
        testStats.set(defs);
    }
}
