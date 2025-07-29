package net.javasauce.ss.tasks.util;

import net.covers1624.quack.collection.FastStream;
import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.task.TaskInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/9/25.
 */
public class GenerateRootProjectTask extends GenProjectTask {

    public final TaskInput<Path> projectDir = input("projectDir");

    public final TaskInput<List<String>> versions = input("versions");
    public final TaskInput<Map<String, CommittedTestCaseDef>> testDefs = input("testDefs");

    private GenerateRootProjectTask(String name, Executor executor) {
        super(name, executor);
    }

    public static GenerateRootProjectTask create(String name, Executor executor, Consumer<GenerateRootProjectTask> cons) {
        var task = new GenerateRootProjectTask(name, executor);
        cons.accept(task);
        return task;
    }

    @Override
    protected void execute() throws Throwable {
        var projectDir = this.projectDir.get();

        Files.writeString(projectDir.resolve(".gitignore"), buildGitIgnore());
        Files.writeString(projectDir.resolve("README.md"), buildReadme());
    }

    private String buildGitIgnore() {
        return """
                # exclude all
                /*
                
                # Include Important Folders
                !cache/
                
                # Include git important files
                !.gitignore
                
                # Other files.
                !README.md
                """;
    }

    private String buildReadme() {
        var testDefs = this.testDefs.get();
        String readme = """
                # Shoveled
                Output of SnowShovel
                """;
        readme += generateReport(
                FastStream.of(versions.get())
                        .filter(testDefs::containsKey)
                        .toMap(e -> e, e -> testDefs.get(e).def())
        );
        return readme;
    }
}
