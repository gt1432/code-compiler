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

    private static class ProcessSession {
        Process process;
        Path tempDir;
        OutputStream outputStream;
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
            if (ps.outputStream != null && ps.process != null && ps.process.isAlive()) {
                try {
                    ps.outputStream.write(payload.getBytes());
                    ps.outputStream.flush();
                } catch (IOException e) {
                    // Process might have died, ignore
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
        
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error:\r\n" + compileError.replace("\n", "\r\n")));
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
        
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error:\r\n" + compileError.replace("\n", "\r\n")));
                }
            }
            return null;
        }

        return new ProcessBuilder(binaryFile.toString()).directory(dir.toFile()).start();
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
        
        String compileError = new String(compileProcess.getErrorStream().readAllBytes());
        if (compileProcess.waitFor() != 0) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage("Compilation Error:\r\n" + compileError.replace("\n", "\r\n")));
                }
            }
            return null;
        }

        return new ProcessBuilder(binaryFile.toString()).directory(dir.toFile()).start();
    }

    private String findCompiler(String compiler) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            if (new ProcessBuilder(compiler, "--version").start().waitFor() == 0) return compiler;
        } catch (Exception ignored) {}

        if (isWindows) {
            String localAppData = System.getenv("LOCALAPPDATA");
            java.util.List<String> commonPaths = new java.util.ArrayList<>();
            
            if (localAppData != null) {
                // Try WinGet LLVM-MinGW path pattern dynamically
                commonPaths.add(localAppData + "\\Microsoft\\WinGet\\Packages\\MartinStorsjo.LLVM-MinGW.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\\llvm-mingw-20260421-ucrt-x86_64\\bin\\");
            }
            commonPaths.add("C:\\MinGW\\bin\\");
            commonPaths.add("C:\\msys64\\mingw64\\bin\\");
            commonPaths.add("C:\\msys64\\usr\\bin\\");

            for (String path : commonPaths) {
                if (new java.io.File(path + compiler + ".exe").exists()) {
                    return path + compiler + ".exe";
                }
            }
        }
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
