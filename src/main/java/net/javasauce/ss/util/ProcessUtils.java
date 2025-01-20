package net.javasauce.ss.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Created by covers1624 on 1/21/25.
 */
public class ProcessUtils {

    public static ProcessResult runProcess(Path executable, List<String> args, Path workingDir, Consumer<String> output) {
        return runProcess(executable.toAbsolutePath().toString(), args, workingDir, output);
    }

    public static ProcessResult runProcess(String executable, List<String> args, Path workingDir, Consumer<String> output) {
        try {
            List<String> realArgs = new ArrayList<>(args.size() + 1);
            realArgs.add(executable);
            realArgs.addAll(args);
            Process proc = new ProcessBuilder(realArgs)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            List<String> outputLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(e -> {
                    output.accept(e);
                    outputLines.add(e);
                });
            }
            proc.waitFor();
            return new ProcessResult(proc.exitValue(), outputLines);
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException("Failed to execute process.", ex);
        }
    }

    public record ProcessResult(int exitCode, List<String> output) {

        public void assertExitCode(int code) {
            if (exitCode != code) {
                throw new RuntimeException("Expected exit code " + code + " got " + exitCode);
            }
        }
    }
}
