package net.javasauce.ss.tasks.util;

import net.javasauce.ss.util.CommittedTestCaseDef;
import net.javasauce.ss.util.ProcessableVersionSet;
import net.javasauce.ss.util.ReportTableGenerator;
import net.javasauce.ss.util.task.Task;
import net.javasauce.ss.util.task.TaskInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 7/9/25.
 */
public class GenerateRootProjectTask extends Task {

    public final TaskInput<Path> projectDir = input("projectDir");

    public final TaskInput<ProcessableVersionSet> versions = input("versions");
    public final TaskInput<Map<String, CommittedTestCaseDef>> testDefs = input("testDefs");
    public final TaskInput<String> gitRepoUrl = input("gitRepoUrl");

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
        var versions = this.versions.get();
        var testDefs = this.testDefs.get();
        var gitRepoUrl = this.gitRepoUrl.get();

        String readme = """
                # Shoveled
                Output of SnowShovel
                """;

        var generator = new ReportTableGenerator();
        for (String id : versions.allVersions()) {
            var testDef = testDefs.get(id);
            if (testDef == null) continue;

            generator.addRow(id, testDef.def(), gitRepoUrl, versions.getManifest(id).computeBranchName());
        }
        readme += generator.build();
        return readme;
    }
}
