package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public class StudyDtos {

    private StudyDtos() {
    }

    public record CreateStudyRequest(
            @NotBlank String studyCode,
            @NotBlank String name) {
    }

    public record CreateParticipantRequest(
            @NotBlank String participantCode,
            @NotBlank String name) {
    }

    public record PublishVersionRequest(
            ChangeType changeType,
            @NotEmpty Set<String> allowedActivityTypes) {
    }

    public record StudyResponse(Long id, String studyCode, String name, Integer currentVersion) {
    }

    public record ParticipantResponse(Long id, String participantCode, String name) {
    }

    public record StudyVersionResponse(
            Long id,
            int versionNo,
            String changeType,
            Set<String> allowedActivityTypes,
            java.time.Instant publishedAt) {
    }
}
