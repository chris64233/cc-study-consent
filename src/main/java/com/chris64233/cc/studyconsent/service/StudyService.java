package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.repo.ParticipantRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import com.chris64233.cc.studyconsent.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 研究、参与者与方案版本发布。
 */
@Service
public class StudyService {

    private final StudyRepository studyRepository;
    private final StudyVersionRepository versionRepository;
    private final ParticipantRepository participantRepository;
    private final Clock clock;

    public StudyService(StudyRepository studyRepository,
                        StudyVersionRepository versionRepository,
                        ParticipantRepository participantRepository,
                        Clock clock) {
        this.studyRepository = studyRepository;
        this.versionRepository = versionRepository;
        this.participantRepository = participantRepository;
        this.clock = clock;
    }

    @Transactional
    public Study createStudy(String studyCode, String name) {
        studyRepository.findByStudyCode(studyCode).ifPresent(s -> {
            throw new ConflictException("研究编码已存在: " + studyCode);
        });
        return studyRepository.save(new Study(studyCode, name));
    }

    @Transactional
    public Participant createParticipant(String participantCode, String name) {
        participantRepository.findByParticipantCode(participantCode).ifPresent(p -> {
            throw new ConflictException("参与者编码已存在: " + participantCode);
        });
        return participantRepository.save(new Participant(participantCode, name));
    }

    /**
     * 发布新版本。
     *
     * <p>持研究行级写锁：版本号严格递增、同一研究同时只有一个当前版本。
     * 已发布版本不可修改（实体不提供更新入口）。</p>
     *
     * @param changeType 首个版本必须为 {@link ChangeType#INITIAL}；其余必须显式声明
     *                   实质/非实质变更
     */
    @Transactional
    public StudyVersion publishVersion(String studyCode,
                                       ChangeType changeType,
                                       Set<String> allowedActivityTypes) {
        if (allowedActivityTypes == null || allowedActivityTypes.isEmpty()) {
            throw new BusinessRuleException("方案版本至少声明一种允许的活动类型");
        }

        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));

        int nextVersionNo = study.getCurrentVersion() == null ? 1 : study.getCurrentVersion() + 1;
        if (nextVersionNo == 1 && changeType != ChangeType.INITIAL) {
            throw new BusinessRuleException("首个版本必须标记为 INITIAL");
        }
        if (nextVersionNo > 1 && changeType != ChangeType.SUBSTANTIVE
                && changeType != ChangeType.NON_SUBSTANTIVE) {
            throw new BusinessRuleException(
                    "后续版本必须标记为 SUBSTANTIVE（实质变更）或 NON_SUBSTANTIVE（非实质变更）");
        }

        StudyVersion version = new StudyVersion(
                study.getId(), nextVersionNo, changeType,
                Set.copyOf(allowedActivityTypes), clock.now());
        versionRepository.save(version);

        study.setCurrentVersion(nextVersionNo);
        studyRepository.save(study);
        return version;
    }

    /** 只读获取研究，不存在抛 404。 */
    @Transactional(readOnly = true)
    public Study getStudy(String studyCode) {
        return studyRepository.findByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));
    }

    @Transactional(readOnly = true)
    public Participant getParticipant(String participantCode) {
        return participantRepository.findByParticipantCode(participantCode)
                .orElseThrow(() -> new NotFoundException("参与者不存在: " + participantCode));
    }
}
