package com.lumina.compiler.controller;

import com.lumina.compiler.model.Submission;
import com.lumina.compiler.model.User;
import com.lumina.compiler.repository.SubmissionRepository;
import com.lumina.compiler.service.CodeExecutionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CodeController {

    private final CodeExecutionService executionService;
    private final SubmissionRepository submissionRepository;

    @PostMapping("/execute")
    public ResponseEntity<?> execute(
            @RequestBody ExecuteRequest request,
            @AuthenticationPrincipal User user
    ) {
        long startTime = System.currentTimeMillis();
        
        // Execute synchronously for now to avoid SecurityContext issues in async threads
        var resultFuture = executionService.executeCode(request.getCode(), request.getLanguage(), request.getInput());
        var executionResult = resultFuture.join();

        long duration = System.currentTimeMillis() - startTime;
        
        Submission submission = Submission.builder()
                .user(user)
                .language(request.getLanguage())
                .code(request.getCode())
                .input(request.getInput())
                .output(executionResult.output() + (executionResult.error().isEmpty() ? "" : "\n--- ERRORS ---\n" + executionResult.error()))
                .exitCode(executionResult.exitCode())
                .executionTimeMs(duration)
                .build();

        try {
            submissionRepository.save(submission);
        } catch (Exception e) {
            System.err.println("Database error saving submission: " + e.getMessage());
        }
        
        return ResponseEntity.ok(executionResult);
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveSnippet(
            @RequestBody ExecuteRequest request,
            @AuthenticationPrincipal User user
    ) {
        Submission submission = Submission.builder()
                .user(user)
                .language(request.getLanguage())
                .code(request.getCode())
                .input(request.getInput() != null ? request.getInput() : "")
                .output("Saved manually.")
                .exitCode(0)
                .executionTimeMs(0L)
                .build();

        try {
            submissionRepository.save(submission);
        } catch (Exception e) {
            System.err.println("Database error saving submission: " + e.getMessage());
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", "Failed to save snippet."));
        }
        
        return ResponseEntity.ok(java.util.Map.of("message", "Snippet saved successfully"));
    }

    @GetMapping("/history")
    public List<Submission> getHistory(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String language
    ) {
        if (language != null && !language.isEmpty()) {
            return submissionRepository.findByUserAndLanguageOrderByCreatedAtDesc(user, language);
        }
        return submissionRepository.findByUserOrderByCreatedAtDesc(user);
    }

    @Data
    public static class ExecuteRequest {
        private String code;
        private String language;
        private String input;
    }
}
