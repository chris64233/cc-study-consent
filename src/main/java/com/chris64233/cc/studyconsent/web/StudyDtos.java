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

    /**
     * 暂停决定请求。activityTypes 为 null/空表示暂停整个研究，
     * 非空时仅暂停给定活动类型。effectiveAt 可空（默认 Clock）。
     */
    public record SuspendRequest(
            @NotBlank String externalEventId,
            @NotBlank String externalIncidentId,
            @NotBlank String reason,
            Set<String> activityTypes,
            java.time.Instant effectiveAt) {
    }

    /** 恢复申请请求；effectiveAt 可空（默认 Clock）。 */
    public record ResumeRequest(
            @NotBlank String externalEventId,
            @NotBlank String suspendEventId,
            @jakarta.validation.constraints.NotNull Integer declaredVersionNo,
            java.time.Instant effectiveAt) {
    }

    public record SuspensionDecisionResponse(
            Long id,
            String externalEventId,
            String decisionType,
            String externalIncidentId,
            String reason,
            String scope,
            Set<String> activityTypes,
            String suspendEventId,
            Integer declaredVersionNo,
            java.time.Instant effectiveAt,
            java.time.Instant recordedAt) {
    }
}
