package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.service.ConsentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/studies/{studyCode}/participants/{participantId}")
public class ConsentController {

    public record SignRequest(@NotBlank String externalEventId,
                              @NotEmpty Set<String> activityTypes) {
    }

    public record WithdrawRequest(@NotBlank String externalEventId,
                                  @NotNull Instant effectiveAt) {
    }

    public record ConsentEventResponse(Long id, String externalEventId, String type,
                                       Integer versionNumber, Set<String> selectedActivityTypes,
                                       Instant signedAt, Instant effectiveAt, Instant recordedAt,
                                       boolean replayed) {
        static ConsentEventResponse of(ConsentEvent e, boolean replayed) {
            return new ConsentEventResponse(e.getId(), e.getExternalEventId(), e.getType().name(),
                    e.getProtocolVersion() == null ? null : e.getProtocolVersion().getVersionNumber(),
                    e.getSelectedActivityTypes().isEmpty() ? null : e.getSelectedActivityTypes(),
                    e.getSignedAt(), e.getEffectiveAt(), e.getRecordedAt(), replayed);
        }
    }

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping("/consents")
    public ResponseEntity<ConsentEventResponse> sign(@PathVariable String studyCode,
                                                     @PathVariable String participantId,
                                                     @Valid @RequestBody SignRequest request) {
        ConsentService.ConsentResult result = consentService.sign(studyCode, participantId,
                request.externalEventId(), request.activityTypes());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(ConsentEventResponse.of(result.event(), result.replayed()));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<ConsentEventResponse> withdraw(@PathVariable String studyCode,
                                                         @PathVariable String participantId,
                                                         @Valid @RequestBody WithdrawRequest request) {
        ConsentService.ConsentResult result = consentService.withdraw(studyCode, participantId,
                request.externalEventId(), request.effectiveAt());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(ConsentEventResponse.of(result.event(), result.replayed()));
    }
}
