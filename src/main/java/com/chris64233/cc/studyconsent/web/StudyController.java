package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.domain.SuspensionDecision;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.SuspensionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class StudyController {

    private final StudyService studyService;
    private final SuspensionService suspensionService;

    public StudyController(StudyService studyService, SuspensionService suspensionService) {
        this.studyService = studyService;
        this.suspensionService = suspensionService;
    }

    @PostMapping("/studies")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyDtos.StudyResponse createStudy(@Valid @RequestBody StudyDtos.CreateStudyRequest request) {
        Study study = studyService.createStudy(request.studyCode(), request.name());
        return new StudyDtos.StudyResponse(study.getId(), study.getStudyCode(),
                study.getName(), study.getCurrentVersion());
    }

    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyDtos.ParticipantResponse createParticipant(
            @Valid @RequestBody StudyDtos.CreateParticipantRequest request) {
        Participant p = studyService.createParticipant(request.participantCode(), request.name());
        return new StudyDtos.ParticipantResponse(p.getId(), p.getParticipantCode(), p.getName());
    }

    @PostMapping("/studies/{studyCode}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyDtos.StudyVersionResponse publishVersion(
            @PathVariable String studyCode,
            @Valid @RequestBody StudyDtos.PublishVersionRequest request) {
        StudyVersion v = studyService.publishVersion(
                studyCode, request.changeType(), request.allowedActivityTypes());
        return new StudyDtos.StudyVersionResponse(v.getId(), v.getVersionNo(),
                v.getChangeType().name(), v.getAllowedActivityTypes(), v.getPublishedAt());
    }

    /** 因安全事件暂停整个研究或某类活动。 */
    @PostMapping("/studies/{studyCode}/suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyDtos.SuspensionDecisionResponse suspend(
            @PathVariable String studyCode,
            @Valid @RequestBody StudyDtos.SuspendRequest request) {
        SuspensionDecision d = suspensionService.suspend(studyCode,
                request.externalEventId(), request.externalIncidentId(), request.reason(),
                request.activityTypes(), request.effectiveAt());
        return toDecisionResponse(d);
    }

    /** 恢复申请：引用当前暂停决定并声明恢复所依据的方案版本。 */
    @PostMapping("/studies/{studyCode}/resumptions")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyDtos.SuspensionDecisionResponse resume(
            @PathVariable String studyCode,
            @Valid @RequestBody StudyDtos.ResumeRequest request) {
        SuspensionDecision d = suspensionService.resume(studyCode,
                request.externalEventId(), request.suspendEventId(),
                request.declaredVersionNo(), request.effectiveAt());
        return toDecisionResponse(d);
    }

    /** 研究暂停/恢复决定的完整、不可修改时间线。 */
    @GetMapping("/studies/{studyCode}/suspensions")
    public List<StudyDtos.SuspensionDecisionResponse> decisions(@PathVariable String studyCode) {
        Study study = studyService.getStudy(studyCode);
        return suspensionService.listDecisions(study.getId()).stream()
                .map(StudyController::toDecisionResponse)
                .toList();
    }

    private static StudyDtos.SuspensionDecisionResponse toDecisionResponse(SuspensionDecision d) {
        return new StudyDtos.SuspensionDecisionResponse(d.getId(), d.getExternalEventId(),
                d.getDecisionType().name(), d.getExternalIncidentId(), d.getReason(),
                d.getScope() == null ? null : d.getScope().name(),
                d.getActivityTypes(), d.getSuspendEventId(), d.getDeclaredVersionNo(),
                d.getEffectiveAt(), d.getRecordedAt());
    }
}
