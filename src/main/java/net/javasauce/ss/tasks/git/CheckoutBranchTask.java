package net.javasauce.ss.tasks.git;

import net.javasauce.ss.util.task.TaskInput;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/29/25.
 */
public class CheckoutBranchTask extends AbstractGitTask {

    public final TaskInput<String> branch = input("branch");
    public final TaskInput<Boolean> clean = input("clean", false);

    private CheckoutBranchTask(String name, Executor executor) {
        super(name, executor);
    }

    public static CheckoutBranchTask create(String name, Executor executor, Consumer<CheckoutBranchTask> cons) {
        var task = new CheckoutBranchTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        checkoutOrCreateBranch(branch.get());
        if (clean.get()) {
            wipeCheckedOutFiles();
        }
    }
}
