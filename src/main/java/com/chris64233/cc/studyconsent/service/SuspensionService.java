package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.DecisionType;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.SuspensionDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScope;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.SuspensionDecisionRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import com.chris64233.cc.studyconsent.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 研究暂停与恢复。
 *
 * <p>暂停/恢复决定持研究行级写锁，与同样在判定阶段取研究锁的活动登记串行；
 * 决定一经写入不可修改、不可删除，构成研究级时间线。</p>
 *
 * <p>暂停不追溯：若暂停范围内已存在发生时间 ≥ 暂停生效时间的合法活动，
 * 暂停决定被拒绝（409），因此"暂停生效后仍被接受的活动"不可能出现。</p>
 */
@Service
public class SuspensionService {

    private final SuspensionDecisionRepository decisionRepository;
    private final StudyRepository studyRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final Clock clock;

    public SuspensionService(SuspensionDecisionRepository decisionRepository,
                             StudyRepository studyRepository,
                             ActivityRecordRepository activityRecordRepository,
                             Clock clock) {
        this.decisionRepository = decisionRepository;
        this.studyRepository = studyRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.clock = clock;
    }

    /**
     * 研究负责人因安全事件暂停整个研究或某类活动。
     *
     * @param externalEventId    本暂停决定的唯一外部事件号（幂等键）
     * @param externalIncidentId 外部安全事件号
     * @param reason             暂停原因
     * @param activityTypes      scope=ACTIVITY_TYPES 时被暂停的活动类型；为 null/空表示整个研究
     * @param effectiveAt        生效时间；为 null 取 Clock
     */
    @Transactional
    public SuspensionDecision suspend(String studyCode,
                                      String externalEventId,
                                      String externalIncidentId,
                                      String reason,
                                      Set<String> activityTypes,
                                      Instant effectiveAt) {
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));

        Set<String> types = normalize(activityTypes);
        SuspensionScope scope = types.isEmpty() ? SuspensionScope.STUDY_WIDE : SuspensionScope.ACTIVITY_TYPES;
        Instant at = effectiveAt != null ? effectiveAt : clock.now();

        SuspensionDecision existing = decisionRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileSuspend(existing, study.getId(), externalIncidentId, reason, scope, types, at,
                    effectiveAt != null);
        }

        if (externalIncidentId == null || externalIncidentId.isBlank()) {
            throw new BusinessRuleException("暂停决定必须记录外部安全事件号");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("暂停决定必须记录原因");
        }

        boolean wholeStudy = scope == SuspensionScope.STUDY_WIDE;
        if (activityRecordRepository.existsBlockingSuspension(
                study.getId(), at, types, wholeStudy)) {
            throw new ConflictException("暂停不能自 " + at + " 起生效：对应范围内已存在发生时间不早于该时点的"
                    + "合法活动记录，暂停不得追溯改写已合法发生的活动");
        }

        return decisionRepository.save(SuspensionDecision.suspend(
                externalEventId, study.getId(), externalIncidentId.trim(), reason.trim(),
                scope, types, at, clock.now()));
    }

    private SuspensionDecision reconcileSuspend(SuspensionDecision existing,
                                                Long studyId,
                                                String externalIncidentId,
                                                String reason,
                                                SuspensionScope scope,
                                                Set<String> types,
                                                Instant at,
                                                boolean timeExplicit) {
        if (existing.getDecisionType() != DecisionType.SUSPEND
                || !existing.getStudyId().equals(studyId)) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已用于不同的决定，内容冲突");
        }
        if (!existing.getExternalIncidentId().equals(externalIncidentId == null ? null : externalIncidentId.trim())
                || !existing.getReason().equals(reason == null ? null : reason.trim())
                || existing.getScope() != scope
                || !existing.getActivityTypes().equals(types)
                || (timeExplicit && !existing.getEffectiveAt().equals(at))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但内容不同（安全事件号/原因/范围/生效时间不一致），冲突");
        }
        return existing;
    }

    /**
     * 恢复申请：引用当前仍生效的暂停决定，并声明恢复所依据的方案版本。
     *
     * <p>暂停期间若发布过实质变更版本，受影响参与者是否真正恢复授权由
     * {@link AuthorizationEvaluator} 按其当前同意版本逐人判断；恢复决定本身
     * 只解除其引用的那一次暂停，不影响其它在效暂停。</p>
     *
     * @param externalEventId   本恢复决定的唯一外部事件号（幂等键）
     * @param suspendEventId    所引用暂停决定的外部事件号
     * @param declaredVersionNo 恢复所依据的方案版本号（必须为研究当前版本）
     * @param effectiveAt       恢复生效时间；为 null 取 Clock
     */
    @Transactional
    public SuspensionDecision resume(String studyCode,
                                     String externalEventId,
                                     String suspendEventId,
                                     Integer declaredVersionNo,
                                     Instant effectiveAt) {
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));

        Instant at = effectiveAt != null ? effectiveAt : clock.now();

        SuspensionDecision existing = decisionRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileResume(existing, study.getId(), suspendEventId, declaredVersionNo, at,
                    effectiveAt != null);
        }

        if (suspendEventId == null || suspendEventId.isBlank()) {
            throw new BusinessRuleException("恢复申请必须引用当前暂停决定的外部事件号");
        }
        SuspensionDecision suspension = decisionRepository.findByExternalEventId(suspendEventId)
                .orElseThrow(() -> new BusinessRuleException("引用的暂停决定不存在: " + suspendEventId));
        if (suspension.getDecisionType() != DecisionType.SUSPEND
                || !suspension.getStudyId().equals(study.getId())) {
            throw new BusinessRuleException("事件号 " + suspendEventId + " 不是本研究的暂停决定");
        }
        if (at.isBefore(suspension.getEffectiveAt())) {
            throw new BusinessRuleException("恢复生效时间 " + at + " 不能早于所引用暂停的生效时间 "
                    + suspension.getEffectiveAt());
        }
        if (decisionRepository.findByStudyIdOrderByEffectiveAtAscIdAsc(study.getId()).stream()
                .anyMatch(d -> d.getDecisionType() == DecisionType.RESUME
                        && suspendEventId.equals(d.getSuspendEventId()))) {
            throw new ConflictException("暂停决定 " + suspendEventId + " 已被恢复，不能重复恢复");
        }

        if (declaredVersionNo == null) {
            throw new BusinessRuleException("恢复申请必须声明所依据的方案版本");
        }
        Integer currentVersionNo = study.getCurrentVersion();
        if (currentVersionNo == null) {
            throw new BusinessRuleException("研究尚未发布任何方案版本");
        }
        if (declaredVersionNo != currentVersionNo) {
            throw new BusinessRuleException("恢复所依据的版本必须是当前版本 v" + currentVersionNo
                    + "，请求声明的是 v" + declaredVersionNo);
        }

        return decisionRepository.save(SuspensionDecision.resume(
                externalEventId, study.getId(), suspendEventId, declaredVersionNo, at, clock.now()));
    }

    private SuspensionDecision reconcileResume(SuspensionDecision existing,
                                               Long studyId,
                                               String suspendEventId,
                                               Integer declaredVersionNo,
                                               Instant at,
                                               boolean timeExplicit) {
        if (existing.getDecisionType() != DecisionType.RESUME
                || !existing.getStudyId().equals(studyId)) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已用于不同的决定，内容冲突");
        }
        if (!existing.getSuspendEventId().equals(suspendEventId)
                || !existing.getDeclaredVersionNo().equals(declaredVersionNo)
                || (timeExplicit && !existing.getEffectiveAt().equals(at))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但内容不同（引用暂停/声明版本/生效时间不一致），冲突");
        }
        return existing;
    }

    /** 某研究的完整暂停/恢复决定时间线。 */
    @Transactional(readOnly = true)
    public List<SuspensionDecision> listDecisions(Long studyId) {
        return decisionRepository.findByStudyIdOrderByEffectiveAtAscIdAsc(studyId);
    }

    private Set<String> normalize(Set<String> activityTypes) {
        if (activityTypes == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String a : activityTypes) {
            if (a == null || a.isBlank()) {
                throw new BusinessRuleException("活动类型不能为空");
            }
            normalized.add(a.trim());
        }
        return normalized;
    }
}
