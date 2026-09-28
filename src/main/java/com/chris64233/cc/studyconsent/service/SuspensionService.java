package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.DecisionType;
import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.ParticipantRepository;
import com.chris64233.cc.studyconsent.repo.StudyDecisionRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import com.chris64233.cc.studyconsent.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 研究暂停与参与者恢复确认。
 *
 * <p>所有写操作与签署/撤回/活动登记共用同一套锁序：<b>先研究行级写锁，
 * 再参与者行级写锁</b>，因此同一研究的暂停、恢复、版本发布、签署、撤回、
 * 活动登记严格串行，判定一律基于研究与参与者的当前状态。</p>
 *
 * <p>暂停采用与撤回对称的不变量：若暂停生效时间点（含）之后已有该范围内的
 * 合法活动落库，则拒绝暂停（409），保证已合法发生的活动不被追溯否定；
 * 暂停生效之后，授权评估直接拒绝该范围内的新活动。</p>
 */
@Service
public class SuspensionService {

    private final StudyDecisionRepository decisionRepository;
    private final StudyRepository studyRepository;
    private final ParticipantRepository participantRepository;
    private final StudyVersionRepository versionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final SuspensionStatusQuery suspensionStatusQuery;
    private final Clock clock;

    public SuspensionService(StudyDecisionRepository decisionRepository,
                             StudyRepository studyRepository,
                             ParticipantRepository participantRepository,
                             StudyVersionRepository versionRepository,
                             ConsentEventRepository consentEventRepository,
                             ActivityRecordRepository activityRecordRepository,
                             SuspensionStatusQuery suspensionStatusQuery,
                             Clock clock) {
        this.decisionRepository = decisionRepository;
        this.studyRepository = studyRepository;
        this.participantRepository = participantRepository;
        this.versionRepository = versionRepository;
        this.consentEventRepository = consentEventRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.suspensionStatusQuery = suspensionStatusQuery;
        this.clock = clock;
    }

    /**
     * 研究负责人因安全事件暂停整个研究或某类活动。
     *
     * @param effectiveAt 生效时间；为 null 取 Clock。暂停不追溯：该时点（含）之后
     *                    已有范围内活动落库时拒绝（409）
     */
    @Transactional
    public StudyDecision suspend(String studyCode,
                                 String externalEventId,
                                 SuspensionScopeType scopeType,
                                 String activityType,
                                 String reason,
                                 Instant effectiveAt) {
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new BusinessRuleException("外部事件号不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("暂停必须记录原因");
        }
        SuspensionScopeType scope = scopeType == null ? SuspensionScopeType.STUDY : scopeType;
        String scopedActivity = null;
        if (scope == SuspensionScopeType.ACTIVITY_TYPE) {
            if (activityType == null || activityType.isBlank()) {
                throw new BusinessRuleException("按活动类型暂停必须指定 activityType");
            }
            scopedActivity = activityType.trim();
        }

        // 先取研究行锁，串行化该研究的全部决定/版本/参与者操作
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));

        Instant eff = effectiveAt != null ? effectiveAt : clock.now();

        StudyDecision existing = decisionRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileSuspend(existing, study.getId(), scope, scopedActivity,
                    reason, eff, effectiveAt != null);
        }

        if (scope == SuspensionScopeType.ACTIVITY_TYPE) {
            Integer currentVersionNo = study.getCurrentVersion();
            if (currentVersionNo == null) {
                throw new BusinessRuleException("研究尚未发布任何方案版本，无法按活动类型暂停");
            }
            StudyVersion current = versionRepository
                    .findByStudyIdAndVersionNo(study.getId(), currentVersionNo)
                    .orElseThrow();
            if (!current.getAllowedActivityTypes().contains(scopedActivity)) {
                throw new BusinessRuleException("活动类型 '" + scopedActivity
                        + "' 不在当前版本 v" + currentVersionNo + " 的允许范围内，无法暂停");
            }
        }

        boolean retroactiveActivity = scope == SuspensionScopeType.ACTIVITY_TYPE
                ? activityRecordRepository.existsByStudyIdAndActivityTypeAndOccurredAtGreaterThanEqual(
                        study.getId(), scopedActivity, eff)
                : activityRecordRepository.existsByStudyIdAndOccurredAtGreaterThanEqual(
                        study.getId(), eff);
        if (retroactiveActivity) {
            throw new ConflictException("不能自 " + eff + " 起暂停：该范围内已存在发生时间不早于该时点的"
                    + "合法活动记录，暂停不得追溯改写已经发生的活动");
        }

        return decisionRepository.save(StudyDecision.suspend(
                externalEventId, study.getId(), scope, scopedActivity, reason.trim(),
                eff, clock.now()));
    }

    private StudyDecision reconcileSuspend(StudyDecision existing,
                                           Long studyId,
                                           SuspensionScopeType scope,
                                           String scopedActivity,
                                           String reason,
                                           Instant eff,
                                           boolean timeExplicit) {
        if (existing.getDecisionType() != DecisionType.SUSPEND
                || !existing.getStudyId().equals(studyId)
                || existing.getScopeType() != scope
                || !java.util.Objects.equals(existing.getScopeActivityType(), scopedActivity)
                || !existing.getReason().equals(reason.trim())
                || (timeExplicit && !existing.getEffectiveAt().equals(eff))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但暂停内容不同（研究/范围/原因/生效时间不一致），冲突");
        }
        return existing;
    }

    /**
     * 参与者（代行人）对当前暂停决定作出恢复确认。
     *
     * <p>恢复申请必须引用其恢复所依据的暂停决定与当前方案版本；暂停期间发布过
     * 实质变更版本的，参与者必须已对当前版本重新签署；同意已撤回者不能借恢复
     * 重新获得授权。恢复只关闭它引用的那次暂停。</p>
     *
     * @param suspensionExternalEventId 所引用暂停决定的外部事件号
     * @param declaredVersionNo         声明恢复所依据的方案版本，必须是当前版本
     * @param effectiveAt               恢复生效时间；为 null 取 Clock
     */
    @Transactional
    public StudyDecision resume(String studyCode,
                                String participantCode,
                                String externalEventId,
                                String suspensionExternalEventId,
                                Integer declaredVersionNo,
                                Instant effectiveAt) {
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new BusinessRuleException("外部事件号不能为空");
        }
        if (suspensionExternalEventId == null || suspensionExternalEventId.isBlank()) {
            throw new BusinessRuleException("恢复申请必须引用当前暂停决定的外部事件号");
        }
        if (declaredVersionNo == null) {
            throw new BusinessRuleException("恢复申请必须声明所依据的方案版本");
        }

        // 锁序：先研究、后参与者
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));
        Participant participant = participantRepository.findByParticipantCode(participantCode)
                .orElseThrow(() -> new NotFoundException("参与者不存在: " + participantCode));
        participantRepository.findForUpdateById(participant.getId()).orElseThrow();

        Instant eff = effectiveAt != null ? effectiveAt : clock.now();

        StudyDecision existing = decisionRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            return reconcileResume(existing, study.getId(), participant.getId(),
                    suspensionExternalEventId, declaredVersionNo, eff, effectiveAt != null);
        }

        StudyDecision suspension = decisionRepository
                .findByExternalEventId(suspensionExternalEventId)
                .orElseThrow(() -> new NotFoundException(
                        "引用的暂停决定不存在: " + suspensionExternalEventId));
        if (suspension.getDecisionType() != DecisionType.SUSPEND
                || !suspension.getStudyId().equals(study.getId())) {
            throw new BusinessRuleException("事件号 " + suspensionExternalEventId + " 不是本研究的暂停决定");
        }
        if (eff.isBefore(suspension.getEffectiveAt())) {
            throw new BusinessRuleException("恢复生效时间不能早于暂停生效时间 "
                    + suspension.getEffectiveAt());
        }

        // 必须声明当前版本：旧恢复请求不能依据过时版本越过新状态
        Integer currentVersionNo = study.getCurrentVersion();
        if (currentVersionNo == null || declaredVersionNo != currentVersionNo) {
            throw new ConflictException("恢复声明依据 v" + declaredVersionNo
                    + "，但研究当前版本为 v" + currentVersionNo
                    + "，请基于当前版本重新提交恢复确认");
        }

        // 引用的必须是该参与者在恢复时点仍然生效的那次暂停；
        // 若后来又有新生效的暂停，activeSuspensionAt 返回的是新暂停 → 拒绝越过
        Optional<StudyDecision> active = suspensionStatusQuery.activeSuspensionAt(
                study.getId(), participant.getId(), suspension.getScopeActivityType(), eff);
        if (active.isEmpty()) {
            throw new ConflictException("引用的暂停决定（事件号 " + suspensionExternalEventId
                    + "）在 " + eff + " 未处于暂停中（可能已恢复），恢复申请必须引用当前暂停决定");
        }
        if (!active.get().getId().equals(suspension.getId())) {
            throw new ConflictException("存在更新的暂停决定（事件号 " + active.get().getExternalEventId()
                    + "）已生效，旧恢复请求不能越过后来的暂停；请引用当前暂停决定重新提交");
        }

        // 基于参与者当前同意状态判断
        List<ConsentEvent> events = consentEventRepository
                .findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(
                        study.getId(), participant.getId());
        ConsentEvent last = null;
        for (ConsentEvent e : events) {
            if (e.getEventTime().isAfter(eff)) {
                break;
            }
            last = e;
        }
        if (last == null) {
            throw new BusinessRuleException("参与者尚未签署知情同意，不存在可恢复的授权");
        }
        if (last.getEventType() == ConsentEventType.WITHDRAW) {
            throw new ConflictException("参与者已于 " + last.getEventTime()
                    + " 撤回全部同意，恢复确认不能使其重新获得授权；须重新签署当前版本后再恢复");
        }

        // 暂停期间发布实质变更版本 → 必须已对当前版本重新签署
        List<StudyVersion> versions =
                versionRepository.findByStudyIdOrderByVersionNoAsc(study.getId());
        for (StudyVersion v : versions) {
            if (v.getChangeType() == com.chris64233.cc.studyconsent.domain.ChangeType.SUBSTANTIVE
                    && v.getVersionNo() > last.getVersionNo()
                    && !v.getPublishedAt().isBefore(suspension.getEffectiveAt())
                    && !v.getPublishedAt().isAfter(eff)) {
                throw new ConflictException("暂停期间发布了实质变更版本 v" + v.getVersionNo()
                        + "，参与者必须先对当前版本 v" + currentVersionNo
                        + " 重新签署，恢复授权才能生效");
            }
        }

        return decisionRepository.save(StudyDecision.resume(
                externalEventId, study.getId(), participant.getId(),
                suspension.getId(), declaredVersionNo, eff, clock.now()));
    }

    private StudyDecision reconcileResume(StudyDecision existing,
                                          Long studyId,
                                          Long participantId,
                                          String suspensionExternalEventId,
                                          Integer declaredVersionNo,
                                          Instant eff,
                                          boolean timeExplicit) {
        if (existing.getDecisionType() != DecisionType.RESUME
                || !existing.getStudyId().equals(studyId)
                || !existing.getParticipantId().equals(participantId)) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已用于不同的决定，内容冲突");
        }
        StudyDecision referenced = decisionRepository.findById(existing.getResumesDecisionId())
                .orElseThrow();
        if (!referenced.getExternalEventId().equals(suspensionExternalEventId)
                || existing.getResumeVersionNo() != declaredVersionNo
                || (timeExplicit && !existing.getEffectiveAt().equals(eff))) {
            throw new ConflictException("外部事件号 " + existing.getExternalEventId()
                    + " 已存在但恢复内容不同（引用暂停/依据版本/生效时间不一致），冲突");
        }
        return existing;
    }

    /** 研究的全部暂停/恢复决定（不可修改时间线），按生效时间升序。 */
    @Transactional(readOnly = true)
    public List<StudyDecision> listDecisions(Long studyId) {
        return decisionRepository.findByStudyIdOrderByEffectiveAtAscIdAsc(studyId);
    }
}
