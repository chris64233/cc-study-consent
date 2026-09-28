package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
import com.chris64233.cc.studyconsent.repo.ParticipantRepository;
import com.chris64233.cc.studyconsent.repo.StudyDecisionRepository;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 研究暂停（研究级）与参与者恢复确认（参与者级），以及不可修改的决定时间线。
 */
@RestController
@RequestMapping("/api")
public class SuspensionController {

    private final SuspensionService suspensionService;
    private final StudyService studyService;
    private final ParticipantRepository participantRepository;
    private final StudyDecisionRepository decisionRepository;

    public SuspensionController(SuspensionService suspensionService,
                                StudyService studyService,
                                ParticipantRepository participantRepository,
                                StudyDecisionRepository decisionRepository) {
        this.suspensionService = suspensionService;
        this.studyService = studyService;
        this.participantRepository = participantRepository;
        this.decisionRepository = decisionRepository;
    }

    /** 研究负责人暂停整个研究或某类活动。 */
    @PostMapping("/studies/{studyCode}/suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionDtos.DecisionResponse suspend(
            @PathVariable String studyCode,
            @Valid @RequestBody SuspensionDtos.SuspendRequest request) {
        SuspensionScopeType scope;
        if (request.scopeType() == null) {
            scope = SuspensionScopeType.STUDY;
        } else {
            try {
                scope = SuspensionScopeType.valueOf(request.scopeType());
            } catch (IllegalArgumentException e) {
                throw new com.chris64233.cc.studyconsent.service.exception.BusinessRuleException(
                        "scopeType 只能是 STUDY 或 ACTIVITY_TYPE: " + request.scopeType());
            }
        }
        StudyDecision d = suspensionService.suspend(studyCode, request.externalEventId(),
                scope, request.activityType(), request.reason(), request.effectiveAt());
        return toResponse(d);
    }

    /** 参与者对当前暂停决定作出恢复确认。 */
    @PostMapping("/studies/{studyCode}/participants/{participantCode}/resumptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionDtos.DecisionResponse resume(
            @PathVariable String studyCode,
            @PathVariable String participantCode,
            @Valid @RequestBody SuspensionDtos.ResumeRequest request) {
        StudyDecision d = suspensionService.resume(studyCode, participantCode,
                request.externalEventId(), request.suspensionExternalEventId(),
                request.versionNo(), request.effectiveAt());
        return toResponse(d);
    }

    /** 研究暂停/恢复决定的完整、不可修改时间线（按生效时间升序）。 */
    @GetMapping("/studies/{studyCode}/decisions")
    public List<SuspensionDtos.DecisionResponse> decisions(@PathVariable String studyCode) {
        Study study = studyService.getStudy(studyCode);
        List<SuspensionDtos.DecisionResponse> responses = new ArrayList<>();
        for (StudyDecision d : suspensionService.listDecisions(study.getId())) {
            responses.add(toResponse(d));
        }
        return responses;
    }

    private SuspensionDtos.DecisionResponse toResponse(StudyDecision d) {
        String participantCode = null;
        if (d.getParticipantId() != null) {
            participantCode = participantRepository.findById(d.getParticipantId())
                    .map(Participant::getParticipantCode).orElse(null);
        }
        String resumesExternalEventId = null;
        if (d.getResumesDecisionId() != null) {
            resumesExternalEventId = decisionRepository.findById(d.getResumesDecisionId())
                    .map(StudyDecision::getExternalEventId).orElse(null);
        }
        return new SuspensionDtos.DecisionResponse(
                d.getId(), d.getExternalEventId(), d.getDecisionType().name(),
                d.getScopeType() == null ? null : d.getScopeType().name(),
                d.getScopeActivityType(), d.getReason(),
                participantCode, d.getResumesDecisionId(), resumesExternalEventId,
                d.getResumeVersionNo(), d.getEffectiveAt(), d.getRecordedAt());
    }
}
