package com.chris64233.cc.studyconsent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.Set;

public class ConsentDtos {

    private ConsentDtos() {
    }

    /** 签署同意请求；versionNo 可空（默认当前版本），signedAt 可空（默认 Clock）。 */
    public record SignConsentRequest(
            @NotBlank String externalEventId,
            Integer versionNo,
            @NotEmpty Set<String> activityTypes,
            Instant signedAt) {
    }

    /** 撤回请求；withdrawAt 可空（默认 Clock）。 */
    public record WithdrawRequest(
            @NotBlank String externalEventId,
            Instant withdrawAt) {
    }

    public record ConsentEventResponse(
            Long id,
            String externalEventId,
            String eventType,
            Integer versionNo,
            Set<String> selectedActivityTypes,
            Instant eventTime,
            Instant recordedAt) {
    }

    /** 活动记录请求；occurredAt 可空（默认 Clock）。 */
    public record RecordActivityRequest(
            @NotBlank String activityType,
            @NotBlank String externalEventId,
            Instant occurredAt) {
    }

    public record ActivityRecordResponse(
            Long id,
            String externalEventId,
            String activityType,
            Instant occurredAt,
            Long authorizedByConsentEventId,
            Integer effectiveVersionNo,
            Instant recordedAt) {
    }
}
