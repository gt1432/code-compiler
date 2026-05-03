package com.lumina.compiler.controller;

import com.lumina.compiler.model.Role;
import com.lumina.compiler.model.User;
import com.lumina.compiler.repository.UserRepository;
import com.lumina.compiler.security.JwtProvider;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final AuthenticationManager authenticationManager;

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .avatarUrl(request.getAvatarUrl())
                .role(Role.USER)
                .build();

        userRepository.save(user);
        String token = jwtProvider.generateToken(user);
        return ResponseEntity.ok(Map.of(
            "token", token, 
            "username", user.getUsername(),
            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (org.springframework.security.core.AuthenticationException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow();
        
        String token = jwtProvider.generateToken(user);
        return ResponseEntity.ok(Map.of(
            "token", token, 
            "username", user.getUsername(),
            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
        ));
    }

    @Data
    public static class SignupRequest {
        private String username;
        private String password;
        private String avatarUrl;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
