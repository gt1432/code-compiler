package com.lumina.compiler.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DBRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "submissions")
public class Submission {

    @Id
    private String id;

    @DBRef
    private User user;

    private String language;

    private String code;

    private String input;

    private String output;

    private Integer exitCode;

    private Long executionTimeMs;

    @CreatedDate
    private LocalDateTime createdAt;
}
