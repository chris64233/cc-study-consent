package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.AuthorizationEvaluator;
import com.chris64233.cc.studyconsent.service.AuthorizationQueryService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.SuspensionService;
import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 研究暂停 / 恢复 / 参与者重新确认流程。
 */
class SuspensionLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private SuspensionService suspensionService;
    @Autowired
    private AuthorizationQueryService queryService;
    @Autowired
    private MutableClock clock;

    private static final String STUDY = "STUDY-S";
    private static final String P = "P-S";
    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        clock.set(T0);
        studyService.createStudy(STUDY, "暂停研究");
        studyService.createParticipant(P, "参与者S");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW", "BLOOD"));
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW", "BLOOD"),
                T0.plus(Duration.ofDays(1)));
    }

    private void assertRefused(String activity, Instant at, DenialReason reason) {
        assertThatThrownBy(() -> activityService.record(STUDY, P, activity, "EVT-" + System.nanoTime(), at))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .satisfies(ex -> assertThat(((ActivityNotAuthorizedException) ex).getRefusal().reason())
                        .isEqualTo(reason));
    }

    // ---------- 规则 1：暂停生效 ----------

    @Test
    void studyWideSuspensionBlocksAllNewActivities() {
        activityService.record(STUDY, P, "INTERVIEW", "A0", T0.plus(Duration.ofDays(2)));

        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-1", SuspensionScopeType.STUDY, null,
                "发生严重不良事件，全面暂停", suspendAt);

        // 生效时点及之后的任何新活动一律拒绝
        assertRefused("INTERVIEW", suspendAt, DenialReason.STUDY_SUSPENDED);
        assertRefused("BLOOD", suspendAt.plusSeconds(10), DenialReason.STUDY_SUSPENDED);

        // 暂停前已合法发生的活动不受影响，且仍可补录暂停生效之前的活动
        var backdated = activityService.record(STUDY, P, "BLOOD", "A-OLD",
                suspendAt.minusSeconds(1));
        assertThat(backdated.getOccurredAt()).isEqualTo(suspendAt.minusSeconds(1));
    }

    @Test
    void activityTypeSuspensionBlocksOnlyThatType() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-2", SuspensionScopeType.ACTIVITY_TYPE,
                "BLOOD", "血样采集设备故障", suspendAt);

        assertRefused("BLOOD", suspendAt, DenialReason.STUDY_SUSPENDED);
        // 其他活动类型仍可登记
        var interview = activityService.record(STUDY, P, "INTERVIEW", "A1",
                suspendAt.plusSeconds(10));
        assertThat(interview).isNotNull();
    }

    @Test
    void activityTypeSuspensionMustTargetAllowedType() {
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SEC-X",
                SuspensionScopeType.ACTIVITY_TYPE, "MRI", "原因", T0.plus(Duration.ofDays(3))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("允许范围");
    }

    @Test
    void suspensionIsRejectedWhenInScopeActivityAlreadyOccurredAtOrAfterEffectiveAt() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        activityService.record(STUDY, P, "INTERVIEW", "A1", suspendAt);

        // 已存在发生于生效时点的活动 → 暂停不能追溯改写，409
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SEC-3",
                SuspensionScopeType.STUDY, null, "安全事件", suspendAt))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("追溯");

        // 活动类型暂停：他类活动不阻止本次暂停
        var bloodOnly = suspensionService.suspend(STUDY, "SEC-4",
                SuspensionScopeType.ACTIVITY_TYPE, "BLOOD", "设备故障", suspendAt);
        assertThat(bloodOnly.getScopeType()).isEqualTo(SuspensionScopeType.ACTIVITY_TYPE);
    }

    @Test
    void suspensionRequiresReason() {
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SEC-5",
                SuspensionScopeType.STUDY, null, "  ", T0.plus(Duration.ofDays(3))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("原因");
    }

    @Test
    void suspensionReplayIsIdempotentButDifferentContentConflicts() {
        Instant at = T0.plus(Duration.ofDays(3));
        StudyDecision first = suspensionService.suspend(STUDY, "SEC-10",
                SuspensionScopeType.ACTIVITY_TYPE, "BLOOD", "设备故障", at);
        StudyDecision replay = suspensionService.suspend(STUDY, "SEC-10",
                SuspensionScopeType.ACTIVITY_TYPE, "BLOOD", "设备故障", at);
        assertThat(replay.getId()).isEqualTo(first.getId());

        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SEC-10",
                SuspensionScopeType.ACTIVITY_TYPE, "BLOOD", "另一个原因", at))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SEC-10",
                SuspensionScopeType.STUDY, null, "设备故障", at))
                .isInstanceOf(ConflictException.class);
    }

    // ---------- 规则 2：恢复与重新确认 ----------

    @Test
    void resumeLiftsSuspensionForThatParticipant() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-20", SuspensionScopeType.STUDY, null,
                "安全事件", suspendAt);
        assertRefused("INTERVIEW", suspendAt.plusSeconds(60), DenialReason.STUDY_SUSPENDED);

        Instant resumeAt = T0.plus(Duration.ofDays(5));
        var resume = suspensionService.resume(STUDY, P, "RES-1", "SEC-20", 1, resumeAt);
        assertThat(resume.getResumeVersionNo()).isEqualTo(1);
        assertThat(resume.getResumesDecisionId()).isNotNull();

        var activity = activityService.record(STUDY, P, "INTERVIEW", "A9",
                resumeAt.plusSeconds(10));
        assertThat(activity).isNotNull();
    }

    @Test
    void resumeMustDeclareCurrentVersion() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-21", SuspensionScopeType.STUDY, null,
                "安全事件", suspendAt);

        // 暂停期间发布非实质变更 v2（新增活动）
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));

        // 声明旧版本 v1 → 冲突，必须基于当前版本
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-X", "SEC-21", 1,
                T0.plus(Duration.ofDays(5))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("当前版本");

        // 声明当前版本 v2 → 通过（非实质变更无需重新签署，共有活动继续授权）
        suspensionService.resume(STUDY, P, "RES-2", "SEC-21", 2,
                T0.plus(Duration.ofDays(5)));
        var interview = activityService.record(STUDY, P, "INTERVIEW", "A1",
                T0.plus(Duration.ofDays(6)));
        assertThat(interview.getEffectiveVersionNo()).isEqualTo(2);
    }

    @Test
    void substantiveVersionDuringSuspensionRequiresResignBeforeResume() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-30", SuspensionScopeType.STUDY, null,
                "安全事件", suspendAt);

        // 暂停期间发布实质变更版本 v2
        Instant v2At = T0.plus(Duration.ofDays(4));
        clock.set(v2At);
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "MRI"));

        // 暂停期间、重新签署前：解释明确指出"暂停后尚未重新同意"
        AuthorizationEvaluator.Result whileSuspended = queryService.explain(
                STUDY, P, "INTERVIEW", v2At.plusSeconds(60));
        assertThat(whileSuspended).isInstanceOf(AuthorizationEvaluator.Result.Refused.class);
        assertThat(((AuthorizationEvaluator.Result.Refused) whileSuspended).reason())
                .isEqualTo(DenialReason.RECONSENT_REQUIRED_AFTER_SUSPENSION);

        // 未重新签署直接恢复 → 冲突，消息明确要求重新签署
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-X", "SEC-30", 2,
                T0.plus(Duration.ofDays(5))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重新签署");

        // 对当前版本重新签署后，恢复成功，授权恢复
        consentService.sign(STUDY, P, "G2", null, Set.of("INTERVIEW", "MRI"),
                T0.plus(Duration.ofDays(5)));
        suspensionService.resume(STUDY, P, "RES-3", "SEC-30", 2,
                T0.plus(Duration.ofDays(5)).plusSeconds(60));
        var mri = activityService.record(STUDY, P, "MRI", "A1", T0.plus(Duration.ofDays(6)));
        assertThat(mri.getEffectiveVersionNo()).isEqualTo(2);
    }

    @Test
    void substantiveVersionBeforeSuspensionDoesNotMarkReconsentAfterSuspensionReason() {
        // 实质变更发生在暂停之前：恢复后仍按普通的"实质变更须重新签署"拒绝
        clock.set(T0.plus(Duration.ofDays(2)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE, Set.of("INTERVIEW", "BLOOD"));
        suspensionService.suspend(STUDY, "SEC-31", SuspensionScopeType.STUDY, null,
                "安全事件", T0.plus(Duration.ofDays(3)));
        // 参与者仍持 v1 同意，恢复时窗口内无新版本 → 恢复本身成功
        suspensionService.resume(STUDY, P, "RES-1", "SEC-31", 2, T0.plus(Duration.ofDays(4)));
        // 但授权仍因实质变更被拒，原因为普通实质变更
        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(5)),
                DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);
    }

    @Test
    void withdrawnParticipantCannotRegainAuthorizationThroughResume() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-40", SuspensionScopeType.STUDY, null,
                "安全事件", suspendAt);
        // 暂停期间撤回同意
        consentService.withdraw(STUDY, P, "W1", T0.plus(Duration.ofDays(4)));

        // 恢复不能让撤回者重新获得授权
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-X", "SEC-40", 1,
                T0.plus(Duration.ofDays(5))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("撤回");

        // 重新签署当前版本后再恢复
        consentService.sign(STUDY, P, "G2", null, Set.of("INTERVIEW"),
                T0.plus(Duration.ofDays(5)));
        suspensionService.resume(STUDY, P, "RES-2", "SEC-40", 1,
                T0.plus(Duration.ofDays(5)).plusSeconds(60));
        var activity = activityService.record(STUDY, P, "INTERVIEW", "A1",
                T0.plus(Duration.ofDays(6)));
        assertThat(activity).isNotNull();
    }

    // ---------- 规则 3：旧恢复不得越过新暂停 ----------

    @Test
    void staleResumeCannotOvertakeNewerSuspension() {
        Instant s1At = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SEC-50", SuspensionScopeType.STUDY, null,
                "第一次安全事件", s1At);
        // 第一次暂停被恢复
        suspensionService.resume(STUDY, P, "RES-1", "SEC-50", 1,
                T0.plus(Duration.ofDays(4)));
        activityService.record(STUDY, P, "INTERVIEW", "A1", T0.plus(Duration.ofDays(4)).plusSeconds(30));

        // 第二次暂停生效
        Instant s2At = T0.plus(Duration.ofDays(6));
        suspensionService.suspend(STUDY, "SEC-51", SuspensionScopeType.STUDY, null,
                "第二次安全事件", s2At);

        // 重放旧恢复（引用第一次暂停）→ 幂等返回原结果，不会关闭第二次暂停
        StudyDecision replay = suspensionService.resume(STUDY, P, "RES-1", "SEC-50", 1,
                T0.plus(Duration.ofDays(4)));
        assertThat(replay.getExternalEventId()).isEqualTo("RES-1");
        assertRefused("INTERVIEW", s2At.plusSeconds(10), DenialReason.STUDY_SUSPENDED);

        // 新的恢复请求若错误引用旧暂停 → 拒绝越过
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-X", "SEC-50", 1,
                T0.plus(Duration.ofDays(7))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("更新的暂停");

        // 必须引用当前暂停才能恢复
        suspensionService.resume(STUDY, P, "RES-2", "SEC-51", 1,
                T0.plus(Duration.ofDays(7)));
        var activity = activityService.record(STUDY, P, "INTERVIEW", "A2",
                T0.plus(Duration.ofDays(8)));
        assertThat(activity).isNotNull();
    }

    @Test
    void resumeReplayIsIdempotentButDifferentContentConflicts() {
        suspensionService.suspend(STUDY, "SEC-60", SuspensionScopeType.STUDY, null,
                "安全事件", T0.plus(Duration.ofDays(3)));
        Instant at = T0.plus(Duration.ofDays(5));
        StudyDecision first = suspensionService.resume(STUDY, P, "RES-60", "SEC-60", 1, at);
        StudyDecision replay = suspensionService.resume(STUDY, P, "RES-60", "SEC-60", 1, at);
        assertThat(replay.getId()).isEqualTo(first.getId());

        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-60", "SEC-60", 1,
                at.plusSeconds(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resumeMustReferenceAnExistingActiveSuspension() {
        // 引用不存在的暂停事件号
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-70", "NO-SUCH", 1,
                T0.plus(Duration.ofDays(3))))
                .isInstanceOf(com.chris64233.cc.studyconsent.service.exception.NotFoundException.class);

        suspensionService.suspend(STUDY, "SEC-70", SuspensionScopeType.STUDY, null,
                "安全事件", T0.plus(Duration.ofDays(3)));
        suspensionService.resume(STUDY, P, "RES-71", "SEC-70", 1, T0.plus(Duration.ofDays(4)));
        // 暂停已恢复，再次引用它恢复 → 409
        assertThatThrownBy(() -> suspensionService.resume(STUDY, P, "RES-72", "SEC-70", 1,
                T0.plus(Duration.ofDays(5))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("当前暂停");
    }

    @Test
    void resumingForOneParticipantDoesNotLiftSuspensionForOthers() {
        studyService.createParticipant("P-OTHER", "其他人");
        consentService.sign(STUDY, "P-OTHER", "G1-OTHER", null, Set.of("INTERVIEW"),
                T0.plus(Duration.ofDays(1)));

        suspensionService.suspend(STUDY, "SEC-80", SuspensionScopeType.STUDY, null,
                "安全事件", T0.plus(Duration.ofDays(3)));
        suspensionService.resume(STUDY, P, "RES-1", "SEC-80", 1, T0.plus(Duration.ofDays(4)));

        var mine = activityService.record(STUDY, P, "INTERVIEW", "A1",
                T0.plus(Duration.ofDays(4)).plusSeconds(30));
        assertThat(mine).isNotNull();
        // P 已恢复，但同一暂停对 P-OTHER 仍然生效
        assertThatThrownBy(() -> activityService.record(STUDY, "P-OTHER", "INTERVIEW", "A2",
                        T0.plus(Duration.ofDays(4)).plusSeconds(30)))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .satisfies(ex -> assertThat(((ActivityNotAuthorizedException) ex).getRefusal().reason())
                        .isEqualTo(DenialReason.STUDY_SUSPENDED));
    }

    // ---------- 规则 4：时间线与解释 ----------

    @Test
    void decisionsAppearOnImmutableTimeline() {
        suspensionService.suspend(STUDY, "SEC-90", SuspensionScopeType.ACTIVITY_TYPE,
                "BLOOD", "设备故障", T0.plus(Duration.ofDays(3)));
        suspensionService.resume(STUDY, P, "RES-90", "SEC-90", 1,
                T0.plus(Duration.ofDays(5)));

        List<AuthorizationQueryService.TimelineEntry> timeline = queryService.timeline(STUDY, P);
        List<AuthorizationQueryService.TimelineEntry.Decision> decisions = timeline.stream()
                .filter(AuthorizationQueryService.TimelineEntry.Decision.class::isInstance)
                .map(AuthorizationQueryService.TimelineEntry.Decision.class::cast)
                .toList();
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).decisionType()).isEqualTo("SUSPEND");
        assertThat(decisions.get(0).scopeType()).isEqualTo("ACTIVITY_TYPE");
        assertThat(decisions.get(0).reason()).isEqualTo("设备故障");
        assertThat(decisions.get(1).decisionType()).isEqualTo("RESUME");
        assertThat(decisions.get(1).resumesExternalEventId()).isEqualTo("SEC-90");
        assertThat(decisions.get(1).resumeVersionNo()).isEqualTo(1);
    }

    @Test
    void statusExplainsSuspensionPerActivity() {
        suspensionService.suspend(STUDY, "SEC-91", SuspensionScopeType.ACTIVITY_TYPE,
                "BLOOD", "设备故障", T0.plus(Duration.ofDays(3)));
        clock.set(T0.plus(Duration.ofDays(4)));

        var snapshot = queryService.status(STUDY, P);
        var byType = snapshot.activityStatuses().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AuthorizationQueryService.ActivityStatus::activityType, s -> s));
        assertThat(byType.get("BLOOD").allowed()).isFalse();
        assertThat(byType.get("BLOOD").denialReason()).isEqualTo("STUDY_SUSPENDED");
        assertThat(byType.get("INTERVIEW").allowed()).isTrue();
    }
}
