package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.service.StudyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
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
}
