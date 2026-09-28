package com.chris64233.cc.studyconsent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class SuspensionDtos {

    private SuspensionDtos() {
    }

    /**
     * 暂停请求。scopeType 可空（缺省 STUDY 暂停整个研究）；
     * ACTIVITY_TYPE 时必须给 activityType；effectiveAt 可空（默认 Clock）。
     */
    public record SuspendRequest(
            @NotBlank String externalEventId,
            String scopeType,
            String activityType,
            @NotBlank String reason,
            Instant effectiveAt) {
    }

    /**
     * 恢复确认请求。必须引用当前暂停决定的外部事件号，并声明依据的当前方案版本；
     * effectiveAt 可空（默认 Clock）。
     */
    public record ResumeRequest(
            @NotBlank String externalEventId,
            @NotBlank String suspensionExternalEventId,
            @NotNull Integer versionNo,
            Instant effectiveAt) {
    }

    /** 暂停/恢复决定响应（不可修改时间线条目）。 */
    public record DecisionResponse(
            Long id,
            String externalEventId,
            String decisionType,
            String scopeType,
            String scopeActivityType,
            String reason,
            String participantCode,
            Long resumesDecisionId,
            String resumesExternalEventId,
            Integer resumeVersionNo,
            Instant effectiveAt,
            Instant recordedAt) {
    }
}
