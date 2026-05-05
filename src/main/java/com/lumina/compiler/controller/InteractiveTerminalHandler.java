package com.lumina.compiler.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class InteractiveTerminalHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Map to hold the process associated with each WebSocket session
    private final Map<String, ProcessSession> sessions = new ConcurrentHashMap<>();
    
    // Cache for compiler paths to speed up lookups
    private static final Map<String, String> compilerCache = new ConcurrentHashMap<>();

    private static class ProcessSession {
        Process process;
        Path tempDir;
        OutputStream outputStream;
        StringBuilder inputBuffer = new StringBuilder(); // Buffer input until process is ready
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Wait for init message
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        ProcessSession ps = sessions.get(session.getId());

        if (ps == null) {
            // This is the first message (init)
            try {
                JsonNode json = objectMapper.readTree(payload);
                String code = json.has("code") ? json.get("code").asText() : "";
                String language = json.has("language") ? json.get("language").asText() : "";

                ps = new ProcessSession();
                sessions.put(session.getId(), ps);

                // Start execution asynchronously
                ProcessSession finalPs = ps;
                executor.submit(() -> handleExecution(session, finalPs, code, language));

            } catch (Exception e) {
                session.sendMessage(new TextMessage("Error parsing init payload: " + e.getMessage()));
                session.close(CloseStatus.BAD_DATA);
            }
        } else {
            // This is an input message
            synchronized (ps) {
                if (ps.outputStream != null && ps.process != null && ps.process.isAlive()) {
                    try {
                        ps.outputStream.write(payload.getBytes());
                        ps.outputStream.flush();
                    } catch (IOException e) {
                        // Process might have died, ignore
                    }
                } else {
                    // Buffer input if process isn't ready or hasn't started yet
                    ps.inputBuffer.append(payload);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ProcessSession ps = sessions.remove(session.getId());
        if (ps != null) {
            if (ps.process != null && ps.process.isAlive()) {
                ps.process.destroyForcibly();
            }
            cleanup(ps.tempDir);
        }
    }

    private void handleExecution(WebSocketSession wsSession, ProcessSession ps, String code, String language) {
        try {
            ps.tempDir = Files.createTempDirectory("lumina_interactive_");
            Process runProcess = null;

            switch (language.toLowerCase()) {
                case "java" -> runProcess = compileAndRunJava(wsSession, ps.tempDir, code);
                case "javascript" -> runProcess = compileAndRunJavaScript(wsSession, ps.tempDir, code);
                case "python" -> runProcess = compileAndRunPython(wsSession, ps.tempDir, code);
                case "c" -> runProcess = compileAndRunC(wsSession, ps.tempDir, code);
                case "cpp" -> runProcess = compileAndRunCpp(wsSession, ps.tempDir, code);
                default -> {
                    wsSession.sendMessage(new TextMessage("Unsupported language: " + language + "\r\n"));
                    wsSession.close(CloseStatus.BAD_DATA);
                    return;
                }
            }

            if (runProcess == null) {
                // Compilation failed, process was not started
                wsSession.close(CloseStatus.NORMAL);
                return;
            }

            ps.process = runProcess;
            ps.outputStream = runProcess.getOutputStream();
            
            // Flush buffered input to the process
            synchronized (ps) {
                if (ps.inputBuffer.length() > 0) {
                    ps.outputStream.write(ps.inputBuffer.toString().getBytes());
                    ps.outputStream.flush();
                    ps.inputBuffer.setLength(0);
                }
            }
            
            final Process finalProcess = runProcess;

            // Stream STDOUT and STDERR to websocket
            executor.submit(() -> streamOutput(finalProcess.getInputStream(), wsSession));
            executor.submit(() -> streamOutput(finalProcess.getErrorStream(), wsSession));

            int exitCode = finalProcess.waitFor();
            if (wsSession.isOpen()) {
                wsSession.sendMessage(new TextMessage("\r\n--- Program exited with code " + exitCode + " ---\r\n"));
                wsSession.close(CloseStatus.NORMAL);
            }

        } catch (Exception e) {
            try {
                if (wsSession.isOpen()) {
                    wsSession.sendMessage(new TextMessage("\r\nExecution Error: " + e.getMessage() + "\r\n"));
                    wsSession.close(CloseStatus.SERVER_ERROR);
                }
            } catch (IOException ignored) {}
        } finally {
            if (ps.process != null && ps.process.isAlive()) {
                ps.process.destroyForcibly();
            }
            cleanup(ps.tempDir);
        }
    }

    private void streamOutput(InputStream is, WebSocketSession session) {
        try {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                if (!session.isOpen()) break;
                String text = new String(buffer, 0, length);
                text = text.replace("\r\n", "\n").replace("\n", "\r\n");
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(text));
                    }
                }
            }
        } catch (IOException e) {
            // Stream closed
        }
    }

    private Process compileAndRunJava(WebSocketSession ws, Path dir, String code) throws Exception {
        String className = "Main";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("public\\s+class\\s+([A-Za-z0-9_]+)").matcher(code);
        if (matcher.find()) {
            className = matcher.group(1);
        }

        Path sourceFile = dir.resolve(className + ".java");
        Files.writeString(sourceFile, code);

        Process compileProcess = new ProcessBuilder("javac", sourceFile.toString())
                .directory(dir.toFile())
                .start();
        
        String compileOutput = new String(compileProcess.getInputStream().readAllBytes());
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        String fullOutput = compileOutput + compileError;

        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error (Java):\r\n" + fullOutput.replace("\n", "\r\n")));
                }
            }
            return null;
        }

        return new ProcessBuilder("java", className)
                .directory(dir.toFile())
                .start();
    }

    private Process compileAndRunJavaScript(WebSocketSession ws, Path dir, String code) throws Exception {
        Path scriptFile = dir.resolve("script.js");
        Files.writeString(scriptFile, code);
        return new ProcessBuilder("node", scriptFile.toString()).directory(dir.toFile()).start();
    }

    private Process compileAndRunPython(WebSocketSession ws, Path dir, String code) throws Exception {
        Path scriptFile = dir.resolve("script.py");
        Files.writeString(scriptFile, code);
        String pythonCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "py" : "python3";
        try {
            new ProcessBuilder(pythonCmd, "--version").start().waitFor();
        } catch (Exception e) {
            pythonCmd = "python";
        }
        // Force python to be unbuffered so output streams immediately
        return new ProcessBuilder(pythonCmd, "-u", scriptFile.toString()).directory(dir.toFile()).start();
    }

    private Process compileAndRunC(WebSocketSession ws, Path dir, String code) throws Exception {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path sourceFile = dir.resolve("program.c");
        Path binaryFile = dir.resolve(isWindows ? "program.exe" : "program");
        Files.writeString(sourceFile, code);

        String gccPath = findCompiler("gcc");
        if (gccPath == null) {
            ws.sendMessage(new TextMessage("Error: GCC compiler not found.\r\n"));
            return null;
        }

        Process compileProcess = new ProcessBuilder(gccPath, sourceFile.toString(), "-o", binaryFile.toString(), "-lm")
                .directory(dir.toFile())
                .start();
        
        String compileOutput = new String(compileProcess.getInputStream().readAllBytes());
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        String fullOutput = compileOutput + compileError;

        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error (C):\r\n" + fullOutput.replace("\n", "\r\n")));
                }
            }
            return null;
        }

        List<String> runCmd = new ArrayList<>();
        if (!isWindows) {
            // Disable buffering on Linux for better interactive experience
            runCmd.addAll(List.of("stdbuf", "-i0", "-o0", "-e0"));
        }
        runCmd.add(binaryFile.toString());

        return new ProcessBuilder(runCmd).directory(dir.toFile()).start();
    }

    private Process compileAndRunCpp(WebSocketSession ws, Path dir, String code) throws Exception {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path sourceFile = dir.resolve("program.cpp");
        Path binaryFile = dir.resolve(isWindows ? "program.exe" : "program");
        Files.writeString(sourceFile, code);

        String gppPath = findCompiler("g++");
        if (gppPath == null) {
            ws.sendMessage(new TextMessage("Error: G++ compiler not found.\r\n"));
            return null;
        }

        Process compileProcess = new ProcessBuilder(gppPath, sourceFile.toString(), "-o", binaryFile.toString(), "-lm")
                .directory(dir.toFile())
                .start();
        
        String compileOutput = new String(compileProcess.getInputStream().readAllBytes());
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        String fullOutput = compileOutput + compileError;

        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error (C++):\r\n" + fullOutput.replace("\n", "\r\n")));
                }
            }
            return null;
        }

        List<String> runCmd = new ArrayList<>();
        if (!isWindows) {
            runCmd.addAll(List.of("stdbuf", "-i0", "-o0", "-e0"));
        }
        runCmd.add(binaryFile.toString());

        return new ProcessBuilder(runCmd).directory(dir.toFile()).start();
    }

    private String findCompiler(String compiler) {
        if (compilerCache.containsKey(compiler)) {
            return compilerCache.get(compiler);
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        // 1. Try standard PATH
        try {
            Process p = new ProcessBuilder(isWindows ? new String[]{"where", compiler} : new String[]{"which", compiler}).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    compilerCache.put(compiler, line.trim());
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}

        // 2. Try common locations
        if (isWindows) {
            String localAppData = System.getenv("LOCALAPPDATA");
            java.util.List<String> commonPaths = new java.util.ArrayList<>();
            
            if (localAppData != null) {
                // Try to find any llvm-mingw version in WinGet packages
                try {
                    Path wingetRoot = Paths.get(localAppData, "Microsoft", "WinGet", "Packages");
                    if (Files.exists(wingetRoot)) {
                        try (var stream = Files.walk(wingetRoot, 3)) {
                            stream.filter(p -> p.toString().contains("MartinStorsjo.LLVM-MinGW") && p.toString().endsWith("bin"))
                                  .filter(p -> Files.exists(p.resolve(compiler + ".exe")))
                                  .findFirst()
                                  .ifPresent(p -> commonPaths.add(p.toString() + "\\"));
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            commonPaths.add("C:\\MinGW\\bin\\");
            commonPaths.add("C:\\msys64\\mingw64\\bin\\");
            commonPaths.add("C:\\msys64\\usr\\bin\\");

            for (String path : commonPaths) {
                File exe = new File(path + compiler + ".exe");
                if (exe.exists()) {
                    String fullPath = exe.getAbsolutePath();
                    compilerCache.put(compiler, fullPath);
                    return fullPath;
                }
            }
        }
        
        // Fallback to compiler name if nothing found, but don't cache negative result
        return null;
    }

    private void cleanup(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignore) {}
                });
        } catch (IOException ignore) {}
    }
}
