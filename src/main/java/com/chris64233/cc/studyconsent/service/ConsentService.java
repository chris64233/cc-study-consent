package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.Enrollment;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.error.BusinessConflictException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.EnrollmentRepository;
import com.chris64233.cc.studyconsent.repo.ProtocolVersionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class ConsentService {

    /** 签署/撤回结果：replayed=true 表示命中幂等重放，返回的是已存在的事件。 */
    public record ConsentResult(ConsentEvent event, boolean replayed) {
    }

    private final StudyService studyService;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final Clock clock;

    public ConsentService(StudyService studyService,
                          ProtocolVersionRepository protocolVersionRepository,
                          ConsentEventRepository consentEventRepository,
                          EnrollmentRepository enrollmentRepository,
                          Clock clock) {
        this.studyService = studyService;
        this.protocolVersionRepository = protocolVersionRepository;
        this.consentEventRepository = consentEventRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    /**
     * 对当前版本签署同意。相同 externalEventId 且内容相同幂等返回；内容不同抛 409。
     */
    @Transactional
    public ConsentResult sign(String studyCode, String participantId, String externalEventId,
                              Set<String> selectedActivityTypes) {
        validateIdentifiers(participantId, externalEventId);
        if (selectedActivityTypes == null || selectedActivityTypes.isEmpty()) {
            throw new BusinessValidationException("必须选择至少一种活动类型");
        }
        Set<String> selected = new LinkedHashSet<>(selectedActivityTypes);
        Study study = studyService.getStudy(studyCode);

        Optional<ConsentEvent> existing = consentEventRepository.findByExternalEventId(externalEventId);
        if (existing.isPresent()) {
            return new ConsentResult(replayOrConflict(existing.get(), study, participantId, selected), true);
        }

        // 锁定登记行，与撤回/活动记录串行化
        Enrollment enrollment = lockOrCreateEnrollment(study, participantId);

        ProtocolVersion current = protocolVersionRepository.findTopByStudyOrderByVersionNumberDesc(study)
                .orElseThrow(() -> new BusinessValidationException("研究尚未发布任何方案版本，无法签署同意"));
        if (!current.getAllowedActivityTypes().containsAll(selected)) {
            throw new BusinessValidationException(
                    "选择的活动类型超出当前方案版本 v" + current.getVersionNumber() + " 允许的范围: "
                            + current.getAllowedActivityTypes());
        }

        Instant now = clock.instant();
        ConsentEvent event = ConsentEvent.sign(externalEventId, enrollment.getStudy(), participantId,
                current, selected, now, now);
        try {
            return new ConsentResult(consentEventRepository.saveAndFlush(event), false);
        } catch (DataIntegrityViolationException e) {
            // 并发下同号事件：本事务回滚，客户端重试即可命中幂等重放或冲突判定
            throw new BusinessConflictException("外部事件号并发冲突，请重试: " + externalEventId);
        }
    }

    /**
     * 在指定生效时间撤回全部同意。撤回事件同样按 externalEventId 幂等。
     */
    @Transactional
    public ConsentResult withdraw(String studyCode, String participantId, String externalEventId,
                                  Instant effectiveAt) {
        validateIdentifiers(participantId, externalEventId);
        if (effectiveAt == null) {
            throw new BusinessValidationException("撤回生效时间不能为空");
        }
        Study study = studyService.getStudy(studyCode);

        Optional<ConsentEvent> existing = consentEventRepository.findByExternalEventId(externalEventId);
        if (existing.isPresent()) {
            return new ConsentResult(replayWithdrawOrConflict(existing.get(), study, participantId, effectiveAt), true);
        }

        Enrollment enrollment = lockOrCreateEnrollment(study, participantId);

        ConsentEvent event = ConsentEvent.withdraw(externalEventId, enrollment.getStudy(), participantId,
                effectiveAt, clock.instant());
        try {
            return new ConsentResult(consentEventRepository.saveAndFlush(event), false);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessConflictException("外部事件号并发冲突，请重试: " + externalEventId);
        }
    }

    private Enrollment lockOrCreateEnrollment(Study study, String participantId) {
        return enrollmentRepository.findByStudyAndParticipantIdForUpdate(study, participantId)
                .orElseGet(() -> enrollmentRepository.saveAndFlush(new Enrollment(study, participantId)));
    }

    private ConsentEvent replayOrConflict(ConsentEvent existing, Study study, String participantId,
                                          Set<String> selected) {
        boolean sameContent = existing.getType() == ConsentEventType.SIGN
                && existing.getStudy().getId().equals(study.getId())
                && existing.getParticipantId().equals(participantId)
                && existing.getSelectedActivityTypes().equals(selected);
        if (!sameContent) {
            throw new BusinessConflictException(
                    "外部事件号已存在且内容不一致: " + existing.getExternalEventId());
        }
        return existing;
    }

    private ConsentEvent replayWithdrawOrConflict(ConsentEvent existing, Study study, String participantId,
                                                  Instant effectiveAt) {
        boolean sameContent = existing.getType() == ConsentEventType.WITHDRAW
                && existing.getStudy().getId().equals(study.getId())
                && existing.getParticipantId().equals(participantId)
                && Objects.equals(existing.getEffectiveAt(), effectiveAt);
        if (!sameContent) {
            throw new BusinessConflictException(
                    "外部事件号已存在且内容不一致: " + existing.getExternalEventId());
        }
        return existing;
    }

    private void validateIdentifiers(String participantId, String externalEventId) {
        if (participantId == null || participantId.isBlank()) {
            throw new BusinessValidationException("参与者不能为空");
        }
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new BusinessValidationException("外部事件号不能为空");
        }
    }
}
