package com.lumina.compiler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@SpringBootApplication
@EnableMongoAuditing
public class OnlineCompilerApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnlineCompilerApplication.class, args);
    }
}
