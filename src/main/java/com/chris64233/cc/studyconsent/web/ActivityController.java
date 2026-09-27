package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.service.ActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/studies/{studyCode}/participants/{participantId}/activities")
public class ActivityController {

    public record RecordActivityRequest(@NotBlank String activityType,
                                        @NotNull Instant occurredAt) {
    }

    public record ActivityResponse(Long id, String activityType, Instant occurredAt,
                                   Instant recordedAt, String authorizedByEventId) {
        static ActivityResponse of(ActivityRecord a) {
            return new ActivityResponse(a.getId(), a.getActivityType(), a.getOccurredAt(),
                    a.getRecordedAt(), a.getAuthorizedBy().getExternalEventId());
        }
    }

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping
    public ResponseEntity<ActivityResponse> record(@PathVariable String studyCode,
                                                   @PathVariable String participantId,
                                                   @Valid @RequestBody RecordActivityRequest request) {
        ActivityRecord record = activityService.record(studyCode, participantId,
                request.activityType(), request.occurredAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ActivityResponse.of(record));
    }
}
