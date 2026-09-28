package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.ParticipantRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同意签署与撤回。
 *
 * <p>所有写操作遵循统一锁序：<b>先研究行级写锁，再参与者行级写锁</b>，
 * 把同一研究的版本发布、暂停/恢复决定与同一参与者的签署、撤回、活动创建
 * 全部串行化；外部事件号由数据库唯一约束兜底。</p>
 */
@org.springframework.stereotype.Service
public class ConsentService {

    private final ConsentEventRepository consentEventRepository;
    private final ParticipantRepository participantRepository;
    private final StudyRepository studyRepository;
    private final StudyVersionRepository versionRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final StudyService studyService;
    private final Clock clock;

    public ConsentService(ConsentEventRepository consentEventRepository,
                          ParticipantRepository participantRepository,
                          StudyRepository studyRepository,
                          StudyVersionRepository versionRepository,
                          ActivityRecordRepository activityRecordRepository,
                          StudyService studyService,
                          Clock clock) {
        this.consentEventRepository = consentEventRepository;
        this.participantRepository = participantRepository;
        this.studyRepository = studyRepository;
        this.versionRepository = versionRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.studyService = studyService;
        this.clock = clock;
    }

    /**
     * 对研究当前版本签署同意。
     *
     * @param externalEventId 调用方唯一事件号；相同号+相同内容幂等，内容不同冲突
     * @param signedAt        签署业务时间；为 null 时取 Clock
     * @param activities      选择的活动类型，必须非空且不超出当前版本允许范围
     */
    @org.springframework.transaction.annotation.Transactional
    public ConsentEvent sign(String studyCode,
                             String participantCode,
                             String externalEventId,
                             Integer explicitVersionNo,
                             Set<String> activities,
                             Instant signedAt) {
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new com.chris64233.cc.studyconsent.service.exception.NotFoundException(
                        "研究不存在: " + studyCode));
        Participant participant = studyService.getParticipant(participantCode);

        // 统一锁序：研究锁之后锁参与者行，串行化并发签署/撤回/活动/暂停恢复
        participantRepository.findForUpdateById(participant.getId()).orElseThrow();

        Instant eventTime = signedAt != null ? signedAt : clock.now();
        Set<String> selected = normalize(activities);

        ConsentEvent existing = consentEventRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileGrant(existing, study.getId(), participant.getId(),
                    explicitVersionNo, selected, eventTime, signedAt != null);
        }

        if (selected.isEmpty()) {
            throw new BusinessRuleException("签署同意至少选择一种活动类型");
        }

        Integer currentVersionNo = study.getCurrentVersion();
        if (currentVersionNo == null) {
            throw new BusinessRuleException("研究尚未发布任何方案版本，无法签署同意");
        }
        int versionNo = explicitVersionNo != null ? explicitVersionNo : currentVersionNo;
        if (versionNo != currentVersionNo) {
            throw new BusinessRuleException("只能对当前版本 v" + currentVersionNo + " 签署同意，请求的是 v"
                    + versionNo);
        }
        StudyVersion version = versionRepository
                .findByStudyIdAndVersionNo(study.getId(), versionNo)
                .orElseThrow(() -> new BusinessRuleException("方案版本不存在: v" + versionNo));
        if (!version.getAllowedActivityTypes().containsAll(selected)) {
            Set<String> overflow = new LinkedHashSet<>(selected);
            overflow.removeAll(version.getAllowedActivityTypes());
            throw new BusinessRuleException("选择的活动类型超出 v" + versionNo + " 允许范围: " + overflow);
        }
        if (eventTime.isBefore(version.getPublishedAt())) {
            throw new BusinessRuleException("签署时间不能早于版本 v" + versionNo + " 的发布时间 "
                    + version.getPublishedAt());
        }

        return consentEventRepository.save(ConsentEvent.grant(
                externalEventId, study.getId(), participant.getId(),
                versionNo, selected, eventTime, clock.now()));
    }

    private ConsentEvent reconcileGrant(ConsentEvent existing,
                                        Long studyId,
                                        Long participantId,
                                        Integer explicitVersionNo,
                                        Set<String> selected,
                                        Instant eventTime,
                                        boolean timeExplicit) {
        if (existing.getEventType() != ConsentEventType.GRANT
                || !existing.getStudyId().equals(studyId)
                || !existing.getParticipantId().equals(participantId)) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已用于不同的同意事件，内容冲突");
        }
        int requestedVersion = explicitVersionNo != null ? explicitVersionNo : existing.getVersionNo();
        if (requestedVersion != existing.getVersionNo()
                || !existing.getSelectedActivityTypes().equals(selected)
                // 调用方未显式提供签署时间时，时间不参与内容比对（重试时 Clock 已变化）
                || (timeExplicit && !existing.getEventTime().equals(eventTime))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但内容不同（版本/活动/签署时间不一致），冲突");
        }
        // 完全相同：幂等返回
        return existing;
    }

    /**
     * 在指定时间撤回参与者对该研究的全部同意。
     *
     * <p>若已存在发生时间不早于撤回生效时间的活动记录，则拒绝撤回，
     * 保证不会出现"撤回生效后仍被接受的活动"。与活动创建并发时，两者靠
     * 参与者行锁串行，后获得锁的一方会看到对方已提交的数据而失败。</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public ConsentEvent withdraw(String studyCode,
                                 String participantCode,
                                 String externalEventId,
                                 Instant withdrawAt) {
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new com.chris64233.cc.studyconsent.service.exception.NotFoundException(
                        "研究不存在: " + studyCode));
        Participant participant = studyService.getParticipant(participantCode);

        participantRepository.findForUpdateById(participant.getId()).orElseThrow();

        Instant eventTime = withdrawAt != null ? withdrawAt : clock.now();

        ConsentEvent existing = consentEventRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileWithdraw(existing, study.getId(), participant.getId(),
                    eventTime, withdrawAt != null);
        }

        if (activityRecordRepository.existsByStudyIdAndParticipantIdAndOccurredAtGreaterThanEqual(
                study.getId(), participant.getId(), eventTime)) {
            throw new ConflictException("不能在 " + eventTime + " 撤回：已存在发生时间不早于该时点的活动记录，"
                    + "撤回生效后不得接受该时点及之后的活动");
        }

        return consentEventRepository.save(ConsentEvent.withdraw(
                externalEventId, study.getId(), participant.getId(), eventTime, clock.now()));
    }

    private ConsentEvent reconcileWithdraw(ConsentEvent existing,
                                           Long studyId,
                                           Long participantId,
                                           Instant eventTime,
                                           boolean timeExplicit) {
        if (existing.getEventType() != ConsentEventType.WITHDRAW
                || !existing.getStudyId().equals(studyId)
                || !existing.getParticipantId().equals(participantId)
                || (timeExplicit && !existing.getEventTime().equals(eventTime))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但内容不同，冲突");
        }
        return existing;
    }

    private Set<String> normalize(Set<String> activities) {
        if (activities == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String a : activities) {
            if (a == null || a.isBlank()) {
                throw new BusinessRuleException("活动类型不能为空");
            }
            normalized.add(a.trim());
        }
        return normalized;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<ConsentEvent> listEvents(Long studyId, Long participantId) {
        return consentEventRepository
                .findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(studyId, participantId);
    }
}
