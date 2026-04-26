package com.lumina.compiler.service;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CodeExecutionService {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public record ExecutionResult(String output, String error, int exitCode) {}

    public CompletableFuture<ExecutionResult> executeCode(String code, String language, String input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runInSubprocess(code, language, input);
            } catch (Exception e) {
                return new ExecutionResult("", "Internal Error: " + e.getMessage(), -1);
            }
        }, executor);
    }

    private ExecutionResult runInSubprocess(String code, String language, String input) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("lumina_");
        try {
            return switch (language.toLowerCase()) {
                case "java" -> executeJava(tempDir, code, input);
                case "javascript" -> executeJavaScript(tempDir, code, input);
                case "python" -> executePython(tempDir, code, input);
                case "c" -> executeC(tempDir, code, input);
                case "cpp" -> executeCpp(tempDir, code, input);
                default -> new ExecutionResult("", "Unsupported language: " + language, -1);
            };
        } finally {
            cleanup(tempDir);
        }
    }

    private ExecutionResult executeJava(Path dir, String code, String input) throws IOException, InterruptedException {
        Path sourceFile = dir.resolve("Main.java");
        Files.writeString(sourceFile, code);

        Process compileProcess = new ProcessBuilder("javac", sourceFile.toString())
                .directory(dir.toFile())
                .start();
        
        ExecutionResult compileRes = captureOutput(compileProcess, null);
        if (compileRes.exitCode() != 0) {
            return new ExecutionResult("", "Compilation Error (Java):\n" + compileRes.error(), compileRes.exitCode());
        }

        Process runProcess = new ProcessBuilder("java", "Main")
                .directory(dir.toFile())
                .start();

        return captureOutput(runProcess, input);
    }

    private ExecutionResult executeJavaScript(Path dir, String code, String input) throws IOException, InterruptedException {
        Path scriptFile = dir.resolve("script.js");
        Files.writeString(scriptFile, code);
        Process p = new ProcessBuilder("node", scriptFile.toString()).directory(dir.toFile()).start();
        return captureOutput(p, input);
    }

    private ExecutionResult executePython(Path dir, String code, String input) throws IOException, InterruptedException {
        Path scriptFile = dir.resolve("script.py");
        Files.writeString(scriptFile, code);
        // Use 'py' launcher on Windows, fallback to 'python'
        Process p = new ProcessBuilder("py", scriptFile.toString()).directory(dir.toFile()).start();
        return captureOutput(p, input);
    }

    private ExecutionResult executeC(Path dir, String code, String input) throws IOException, InterruptedException {
        Path sourceFile = dir.resolve("program.c");
        Path binaryFile = dir.resolve("program.exe");
        Files.writeString(sourceFile, code);

        String gccPath = findCompiler("gcc");
        if (gccPath == null) {
            return new ExecutionResult("", "Error: C compiler (gcc) is not installed or not in the system PATH. Please install GCC to run C programs.", -1);
        }

        try {
            Process compileProcess = new ProcessBuilder(gccPath, sourceFile.toString(), "-o", binaryFile.toString())
                    .directory(dir.toFile())
                    .start();
            
            ExecutionResult compileRes = captureOutput(compileProcess, null);
            if (compileRes.exitCode() != 0) {
                return new ExecutionResult("", "Compilation Error (C):\n" + compileRes.error(), compileRes.exitCode());
            }

            Process runProcess = new ProcessBuilder(binaryFile.toString()).directory(dir.toFile()).start();
            return captureOutput(runProcess, input);
        } catch (IOException e) {
            if (e.getMessage().contains("Cannot run program \"gcc\"")) {
                return new ExecutionResult("", "Error: C compiler (gcc) is not installed or not in the system PATH. Please install GCC to run C programs.", -1);
            }
            throw e;
        }
    }

    private ExecutionResult executeCpp(Path dir, String code, String input) throws IOException, InterruptedException {
        Path sourceFile = dir.resolve("program.cpp");
        Path binaryFile = dir.resolve("program.exe");
        Files.writeString(sourceFile, code);

        String gppPath = findCompiler("g++");
        if (gppPath == null) {
            return new ExecutionResult("", "Error: C++ compiler (g++) is not installed or not in the system PATH. Please install G++ to run C++ programs.", -1);
        }

        try {
            Process compileProcess = new ProcessBuilder(gppPath, sourceFile.toString(), "-o", binaryFile.toString())
                    .directory(dir.toFile())
                    .start();
            
            ExecutionResult compileRes = captureOutput(compileProcess, null);
            if (compileRes.exitCode() != 0) {
                return new ExecutionResult("", "Compilation Error (C++):\n" + compileRes.error(), compileRes.exitCode());
            }

            Process runProcess = new ProcessBuilder(binaryFile.toString()).directory(dir.toFile()).start();
            return captureOutput(runProcess, input);
        } catch (IOException e) {
            if (e.getMessage().contains("Cannot run program \"g++\"")) {
                return new ExecutionResult("", "Error: C++ compiler (g++) is not installed or not in the system PATH. Please install G++ to run C++ programs.", -1);
            }
            throw e;
        }
    }
    private String findCompiler(String compiler) {
        // Try standard PATH first
        try {
            Process process = new ProcessBuilder(compiler, "--version").start();
            if (process.waitFor() == 0) return compiler;
        } catch (Exception ignored) {}

        // Try common installation paths including the one we just found
        String[] commonPaths = {
            "C:\\Users\\gt\\AppData\\Local\\Microsoft\\WinGet\\Packages\\MartinStorsjo.LLVM-MinGW.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\\llvm-mingw-20260421-ucrt-x86_64\\bin\\",
            "C:\\MinGW\\bin\\",
            "C:\\msys64\\mingw64\\bin\\",
            "C:\\msys64\\usr\\bin\\"
        };

        for (String path : commonPaths) {
            String fullPath = path + compiler + ".exe";
            if (new java.io.File(fullPath).exists()) {
                return fullPath;
            }
        }
        return null;
    }


    private ExecutionResult captureOutput(Process process, String input) throws IOException, InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Use Virtual Threads to read streams concurrently (avoids pipe deadlock)
        Future<?> outReader = executor.submit(() -> readStream(process.getInputStream(), stdout));
        Future<?> errReader = executor.submit(() -> readStream(process.getErrorStream(), stderr));

        try {
            if (input != null && !input.isEmpty()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(input.getBytes());
                    os.flush();
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(stdout.toString(), stderr.toString() + "\nExecution Timeout (10s)", -1);
            }

            // Ensure readers are done (wait a bit more than the process wait just in case)
            outReader.get(1, TimeUnit.SECONDS);
            errReader.get(1, TimeUnit.SECONDS);

            return new ExecutionResult(stdout.toString(), stderr.toString(), process.exitValue());
        } catch (Exception e) {
            process.destroyForcibly();
            return new ExecutionResult(stdout.toString(), stderr.toString() + "\nExecution Error: " + e.getMessage(), -1);
        }
    }

    private void readStream(InputStream is, StringBuilder sb) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private void cleanup(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignore) {}
                });
        } catch (IOException ignore) {}
    }
}
