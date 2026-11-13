package com.jobpilot.apply;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnswerBankRepository extends JpaRepository<AnswerBank, UUID> {
    List<AnswerBank> findByUserId(UUID userId);
    Optional<AnswerBank> findByUserIdAndQuestionKey(UUID userId, String questionKey);
}
