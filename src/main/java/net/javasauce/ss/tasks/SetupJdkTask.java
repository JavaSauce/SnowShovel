package net.javasauce.ss.tasks;

import net.covers1624.jdkutils.JavaVersion;
import net.javasauce.ss.util.JdkProvider;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;
import net.javasauce.ss.util.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 6/28/25.
 */
public class SetupJdkTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupJdkTask.class);

    private final JdkProvider provider;

    public final TaskInput<JavaVersion> javaVersion = input("javaVersion");
    public final TaskOutput<Path> javaHome = computedOutput("jdkHome");

    private SetupJdkTask(String name, Executor executor, JdkProvider provider) {
        super(name, executor);
        this.provider = provider;
    }

    public static SetupJdkTask create(String name, Executor executor, JdkProvider provider, Consumer<SetupJdkTask> cons) {
        SetupJdkTask task = new SetupJdkTask(name, executor, provider);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        LOGGER.info("Setting up jdk for {}", javaVersion);
        var home = provider.findOrProvisionJdk(javaVersion.get());
        LOGGER.info("Selected jdk {}", home);
        javaHome.set(home);
    }
}
