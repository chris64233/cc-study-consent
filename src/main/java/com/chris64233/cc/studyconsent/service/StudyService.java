package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.error.BusinessConflictException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.error.ResourceNotFoundException;
import com.chris64233.cc.studyconsent.repo.ProtocolVersionRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;

@Service
public class StudyService {

    private final StudyRepository studyRepository;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final Clock clock;

    public StudyService(StudyRepository studyRepository,
                        ProtocolVersionRepository protocolVersionRepository,
                        Clock clock) {
        this.studyRepository = studyRepository;
        this.protocolVersionRepository = protocolVersionRepository;
        this.clock = clock;
    }

    @Transactional
    public Study createStudy(String code, String name) {
        if (code == null || code.isBlank()) {
            throw new BusinessValidationException("研究编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessValidationException("研究名称不能为空");
        }
        studyRepository.findByCode(code).ifPresent(s -> {
            throw new BusinessConflictException("研究编号已存在: " + code);
        });
        return studyRepository.save(new Study(code, name));
    }

    /**
     * 发布新版本。版本号在研究行悲观锁内取当前最大版本号 +1，保证严格递增；
     * 已发布版本不提供任何修改入口。首个版本没有上一版本，changeType 必须为 null；
     * 后续版本必须声明变更性质。
     */
    @Transactional
    public ProtocolVersion publishVersion(String studyCode, Set<String> allowedActivityTypes,
                                          ChangeType changeType) {
        if (allowedActivityTypes == null || allowedActivityTypes.isEmpty()) {
            throw new BusinessValidationException("方案版本必须声明至少一种允许的活动类型");
        }
        if (allowedActivityTypes.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new BusinessValidationException("活动类型不能为空");
        }
        Study study = studyRepository.findByCodeForUpdate(studyCode)
                .orElseThrow(() -> new ResourceNotFoundException("研究不存在: " + studyCode));

        int nextNumber = protocolVersionRepository.findTopByStudyOrderByVersionNumberDesc(study)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);

        if (nextNumber == 1 && changeType != null) {
            throw new BusinessValidationException("首个版本没有上一版本，不应声明变更性质");
        }
        if (nextNumber > 1 && changeType == null) {
            throw new BusinessValidationException("非首个版本必须声明相对上一版本的变更性质");
        }

        ProtocolVersion version = new ProtocolVersion(study, nextNumber, changeType,
                allowedActivityTypes, clock.instant());
        return protocolVersionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<ProtocolVersion> listVersions(String studyCode) {
        Study study = getStudy(studyCode);
        return protocolVersionRepository.findByStudyOrderByVersionNumberAsc(study);
    }

    @Transactional(readOnly = true)
    public Study getStudy(String studyCode) {
        return studyRepository.findByCode(studyCode)
                .orElseThrow(() -> new ResourceNotFoundException("研究不存在: " + studyCode));
    }
}
