package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.AuthorizationQueryService;
import com.chris64233.cc.studyconsent.service.AuthorizationEvaluator;
import com.chris64233.cc.studyconsent.service.ConsentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/studies/{studyCode}/participants/{participantCode}")
public class ConsentController {

    private final ConsentService consentService;
    private final ActivityService activityService;
    private final AuthorizationQueryService queryService;

    public ConsentController(ConsentService consentService,
                             ActivityService activityService,
                             AuthorizationQueryService queryService) {
        this.consentService = consentService;
        this.activityService = activityService;
        this.queryService = queryService;
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentDtos.ConsentEventResponse sign(
            @PathVariable String studyCode,
            @PathVariable String participantCode,
            @Valid @RequestBody ConsentDtos.SignConsentRequest request) {
        ConsentEvent e = consentService.sign(studyCode, participantCode,
                request.externalEventId(), request.versionNo(),
                request.activityTypes(), request.signedAt());
        return toResponse(e);
    }

    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentDtos.ConsentEventResponse withdraw(
            @PathVariable String studyCode,
            @PathVariable String participantCode,
            @Valid @RequestBody ConsentDtos.WithdrawRequest request) {
        ConsentEvent e = consentService.withdraw(studyCode, participantCode,
                request.externalEventId(), request.withdrawAt());
        return toResponse(e);
    }

    @PostMapping("/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentDtos.ActivityRecordResponse recordActivity(
            @PathVariable String studyCode,
            @PathVariable String participantCode,
            @Valid @RequestBody ConsentDtos.RecordActivityRequest request) {
        ActivityRecord a = activityService.record(studyCode, participantCode,
                request.activityType(), request.externalEventId(), request.occurredAt());
        return new ConsentDtos.ActivityRecordResponse(a.getId(), a.getExternalEventId(),
                a.getActivityType(), a.getOccurredAt(), a.getAuthorizedByConsentEventId(),
                a.getEffectiveVersionNo(), a.getRecordedAt());
    }

    @GetMapping("/authorization/status")
    public AuthorizationQueryService.AuthorizationSnapshot status(
            @PathVariable String studyCode,
            @PathVariable String participantCode) {
        return queryService.status(studyCode, participantCode);
    }

    @GetMapping("/timeline")
    public List<AuthorizationQueryService.TimelineEntry> timeline(
            @PathVariable String studyCode,
            @PathVariable String participantCode) {
        return queryService.timeline(studyCode, participantCode);
    }

    @GetMapping("/authorization/explain")
    public ExplainResponse explain(
            @PathVariable String studyCode,
            @PathVariable String participantCode,
            @RequestParam String activityType,
            @RequestParam(required = false) Instant at) {
        AuthorizationEvaluator.Result result =
                queryService.explain(studyCode, participantCode, activityType, at);
        if (result instanceof AuthorizationEvaluator.Result.Allowed allowed) {
            return new ExplainResponse(true, null, allowed.message(),
                    allowed.effectiveVersionNo(), allowed.consentEvent().getId());
        }
        AuthorizationEvaluator.Result.Refused refused = (AuthorizationEvaluator.Result.Refused) result;
        return new ExplainResponse(false, refused.reason().name(), refused.message(),
                refused.effectiveVersionNo(),
                refused.lastEvent() == null ? null : refused.lastEvent().getId());
    }

    /** 授权解释响应。 */
    public record ExplainResponse(
            boolean allowed,
            String denialReason,
            String message,
            Integer effectiveVersionNo,
            Long consentEventId) {
    }

    private static ConsentDtos.ConsentEventResponse toResponse(ConsentEvent e) {
        return new ConsentDtos.ConsentEventResponse(e.getId(), e.getExternalEventId(),
                e.getEventType().name(), e.getVersionNo(), e.getSelectedActivityTypes(),
                e.getEventTime(), e.getRecordedAt());
    }
}
