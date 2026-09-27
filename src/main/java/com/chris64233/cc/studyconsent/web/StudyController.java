package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.service.StudyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/studies")
public class StudyController {

    public record CreateStudyRequest(@NotBlank String code, @NotBlank String name) {
    }

    public record PublishVersionRequest(@NotEmpty Set<String> allowedActivityTypes,
                                        ChangeType changeType) {
    }

    public record StudyResponse(Long id, String code, String name) {
    }

    public record VersionResponse(Long id, int versionNumber, ChangeType changeType,
                                  Set<String> allowedActivityTypes, Instant publishedAt) {
        static VersionResponse of(ProtocolVersion v) {
            return new VersionResponse(v.getId(), v.getVersionNumber(), v.getChangeType(),
                    v.getAllowedActivityTypes(), v.getPublishedAt());
        }
    }

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    @PostMapping
    public ResponseEntity<StudyResponse> createStudy(@Valid @RequestBody CreateStudyRequest request) {
        Study study = studyService.createStudy(request.code(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new StudyResponse(study.getId(), study.getCode(), study.getName()));
    }

    @PostMapping("/{code}/versions")
    public ResponseEntity<VersionResponse> publishVersion(@PathVariable String code,
                                                          @Valid @RequestBody PublishVersionRequest request) {
        ProtocolVersion version = studyService.publishVersion(code, request.allowedActivityTypes(),
                request.changeType());
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionResponse.of(version));
    }

    @GetMapping("/{code}/versions")
    public List<VersionResponse> listVersions(@PathVariable String code) {
        return studyService.listVersions(code).stream().map(VersionResponse::of).toList();
    }
}
