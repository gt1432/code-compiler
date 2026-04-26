package com.lumina.compiler.repository;

import com.lumina.compiler.model.Submission;
import com.lumina.compiler.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface SubmissionRepository extends MongoRepository<Submission, String> {
    List<Submission> findByUserOrderByCreatedAtDesc(User user);
    List<Submission> findByUserAndLanguageOrderByCreatedAtDesc(User user, String language);
}
