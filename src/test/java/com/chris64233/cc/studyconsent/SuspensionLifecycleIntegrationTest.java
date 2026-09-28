package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.domain.SuspensionDecision;
import com.chris64233.cc.studyconsent.service.ActivityService;
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
 * 研究暂停、恢复与参与者重新确认流程：
 * 暂停范围与不追溯、恢复引用与版本声明、暂停期实质变更须重新同意、
 * 旧恢复不得越过新暂停、撤回者不因恢复而重新获得授权、幂等/冲突、时间线。
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
        studyService.createParticipant(P, "暂停参与者");
        studyService.publishVersion(STUDY, ChangeType.INITIAL,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));
        consentService.sign(STUDY, P, "G1", null,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"), T0.plus(Duration.ofDays(1)));
        activityService.record(STUDY, P, "INTERVIEW", "A0", T0.plus(Duration.ofDays(2)));
    }

    private void assertRefused(String activity, Instant at, DenialReason reason) {
        assertThatThrownBy(() -> activityService.record(STUDY, P, activity, "EVT-" + System.nanoTime(), at))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .satisfies(ex -> assertThat(((ActivityNotAuthorizedException) ex).getRefusal().reason())
                        .isEqualTo(reason));
    }

    // ---------- 规则 1：暂停生效与不追溯 ----------

    @Test
    void wholeStudySuspensionBlocksAllNewActivitiesButKeepsPastOnes() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        SuspensionDecision s = suspensionService.suspend(STUDY, "SUS-1", "INC-42",
                "发现严重不良事件", null, suspendAt);

        assertThat(s.getScope().name()).isEqualTo("STUDY_WIDE");
        assertThat(s.getExternalIncidentId()).isEqualTo("INC-42");

        // 生效时点及之后，任何活动类型都不得登记（含事后补录）
        assertRefused("INTERVIEW", suspendAt, DenialReason.STUDY_SUSPENDED);
        assertRefused("BLOOD", suspendAt.plus(Duration.ofDays(1)), DenialReason.STUDY_SUSPENDED);
        assertRefused("SURVEY", T0.plus(Duration.ofDays(10)), DenialReason.STUDY_SUSPENDED);

        // 暂停前已合法发生的活动不受影响：时间线中仍为接受
        var timeline = queryService.timeline(STUDY, P);
        var pastActivity = timeline.stream()
                .filter(e -> e instanceof AuthorizationQueryService.TimelineEntry.Activity)
                .map(e -> (AuthorizationQueryService.TimelineEntry.Activity) e)
                .filter(a -> "A0".equals(a.externalEventId()))
                .findFirst().orElseThrow();
        assertThat(pastActivity.allowed()).isTrue();

        // explain 明确指出因研究暂停而拒绝
        var result = queryService.explain(STUDY, P, "INTERVIEW", suspendAt.plusSeconds(60));
        assertThat(result).isInstanceOf(com.chris64233.cc.studyconsent.service.AuthorizationEvaluator
                .Result.Refused.class);
        assertThat(((com.chris64233.cc.studyconsent.service.AuthorizationEvaluator.Result.Refused) result)
                .reason()).isEqualTo(DenialReason.STUDY_SUSPENDED);
    }

    @Test
    void activityScopedSuspensionBlocksOnlySuspendedTypes() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SUS-BLOOD", "INC-7", "采血设备异常",
                Set.of("BLOOD"), suspendAt);

        // BLOOD 被暂停
        assertRefused("BLOOD", suspendAt, DenialReason.STUDY_SUSPENDED);
        // 其它活动照常登记
        var accepted = activityService.record(STUDY, P, "INTERVIEW", "A1",
                suspendAt.plus(Duration.ofDays(1)));
        assertThat(accepted).isNotNull();
    }

    @Test
    void suspensionCannotRetroactivelyInvalidateLawfulActivities() {
        // 暂停生效时间早于/等于范围内已接受活动 → 拒绝暂停（409）
        Instant later = T0.plus(Duration.ofDays(10));
        activityService.record(STUDY, P, "BLOOD", "A-LATE", later);

        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-X", "INC-1",
                "原因", null, later))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("追溯");

        // 活动级暂停只检查其范围内的活动：BLOOD 有 later 活动 → 冲突
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-Y", "INC-1",
                "原因", Set.of("BLOOD"), later))
                .isInstanceOf(ConflictException.class);

        // 范围外的活动不阻塞：SURVEY 没有 later 时点的活动，可暂停
        SuspensionDecision ok = suspensionService.suspend(STUDY, "SUS-Z", "INC-1",
                "原因", Set.of("SURVEY"), later);
        assertThat(ok.getActivityTypes()).containsExactly("SURVEY");
    }

    @Test
    void suspensionRequiresIncidentIdAndReason() {
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-1", "  ",
                "原因", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("安全事件号");
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-1", "INC-1",
                " ", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("原因");
    }

    // ---------- 规则 2：恢复引用、版本声明与重新同意 ----------

    @Test
    void resumeMustReferenceAnActiveSuspensionAndDeclareCurrentVersion() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SUS-1", "INC-42", "原因", null, suspendAt);

        // 引用不存在的暂停
        assertThatThrownBy(() -> suspensionService.resume(STUDY, "R1", "NO-SUCH", 1, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("暂停决定不存在");

        // 声明版本不是当前版本
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY", "MRI"));
        assertThatThrownBy(() -> suspensionService.resume(STUDY, "R1", "SUS-1", 1,
                        T0.plus(Duration.ofDays(6))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("当前版本 v2");

        // 合法恢复
        SuspensionDecision r = suspensionService.resume(STUDY, "R1", "SUS-1", 2,
                T0.plus(Duration.ofDays(8)));
        assertThat(r.getDecisionType().name()).isEqualTo("RESUME");
        assertThat(r.getSuspendEventId()).isEqualTo("SUS-1");
        assertThat(r.getDeclaredVersionNo()).isEqualTo(2);

        // 恢复后新活动可登记
        var accepted = activityService.record(STUDY, P, "INTERVIEW", "A2",
                T0.plus(Duration.ofDays(9)));
        assertThat(accepted.getEffectiveVersionNo()).isEqualTo(2);

        // 不能重复恢复同一暂停
        assertThatThrownBy(() -> suspensionService.resume(STUDY, "R2", "SUS-1", 2,
                T0.plus(Duration.ofDays(9))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已被恢复");
    }

    @Test
    void substantiveVersionDuringSuspensionRequiresReconsentAfterResume() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SUS-1", "INC-42", "原因", null, suspendAt);

        // 暂停期间发布实质变更 v2
        clock.set(T0.plus(Duration.ofDays(4)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "MRI"));

        // 恢复声明 v2
        suspensionService.resume(STUDY, "R1", "SUS-1", 2, T0.plus(Duration.ofDays(5)));

        // 旧同意（v1）不能因恢复而复活：即使活动类型未变也须重新签署
        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(6)), DenialReason.RECONSENT_REQUIRED);
        assertRefused("BLOOD", T0.plus(Duration.ofDays(6)), DenialReason.RECONSENT_REQUIRED);

        // 对当前版本重新签署后恢复授权
        consentService.sign(STUDY, P, "G2", null,
                Set.of("INTERVIEW", "MRI"), T0.plus(Duration.ofDays(7)));
        var accepted = activityService.record(STUDY, P, "MRI", "A3", T0.plus(Duration.ofDays(8)));
        assertThat(accepted.getEffectiveVersionNo()).isEqualTo(2);
        // BLOOD 未重新选择
        assertRefused("BLOOD", T0.plus(Duration.ofDays(8)), DenialReason.ACTIVITY_NOT_SELECTED);
    }

    @Test
    void resigningDuringSuspensionMeansNoFurtherReconsentNeededAfterResume() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SUS-1", "INC-42", "原因", null, suspendAt);

        clock.set(T0.plus(Duration.ofDays(4)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));

        // 暂停期间（签署不是研究活动）对当前版本重新签署
        consentService.sign(STUDY, P, "G2", null,
                Set.of("INTERVIEW"), T0.plus(Duration.ofDays(5)));
        suspensionService.resume(STUDY, "R1", "SUS-1", 2, T0.plus(Duration.ofDays(6)));

        // 恢复后直接可登记，无需再次重新同意
        var accepted = activityService.record(STUDY, P, "INTERVIEW", "A3",
                T0.plus(Duration.ofDays(7)));
        assertThat(accepted).isNotNull();
    }

    @Test
    void nonSubstantiveChangeDuringSuspensionFollowsSharedActivityRules() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        suspensionService.suspend(STUDY, "SUS-1", "INC-42", "原因", null, suspendAt);

        // 暂停期仅发布非实质变更：新增 MRI，保留 INTERVIEW/BLOOD/SURVEY
        clock.set(T0.plus(Duration.ofDays(4)));
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY", "MRI"));
        suspensionService.resume(STUDY, "R1", "SUS-1", 2, T0.plus(Duration.ofDays(5)));

        // 共有活动继续由旧同意授权，无需重新签署
        var accepted = activityService.record(STUDY, P, "INTERVIEW", "A3",
                T0.plus(Duration.ofDays(6)));
        assertThat(accepted.getEffectiveVersionNo()).isEqualTo(2);
        // 新增活动未被旧同意选择
        assertRefused("MRI", T0.plus(Duration.ofDays(6)), DenialReason.ACTIVITY_NOT_SELECTED);
    }

    @Test
    void reconsentRequirementIsScopedToSuspendedActivities() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        // 仅暂停 BLOOD
        suspensionService.suspend(STUDY, "SUS-1", "INC-42", "原因",
                Set.of("BLOOD"), suspendAt);

        clock.set(T0.plus(Duration.ofDays(4)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));
        suspensionService.resume(STUDY, "R1", "SUS-1", 2, T0.plus(Duration.ofDays(5)));

        // BLOOD 在暂停覆盖范围内 → 须重新同意
        assertRefused("BLOOD", T0.plus(Duration.ofDays(6)), DenialReason.RECONSENT_REQUIRED);
        // INTERVIEW 从未被暂停：实质变更对其的影响按常规规则——
        // v1 签署在 v2 实质变更之前 → SUBSTANTIVE_VERSION_PUBLISHED（而非 RECONSENT_REQUIRED）
        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(6)),
                DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);
    }

    // ---------- 规则 3：恢复与后来的暂停/撤回并发的语义 ----------

    @Test
    void oldResumeCannotLiftALaterSuspension() {
        // S1 暂停 → 之后 S2 再次暂停 → R1 只解除 S1，S2 仍生效
        suspensionService.suspend(STUDY, "S1", "INC-1", "第一次",
                null, T0.plus(Duration.ofDays(3)));
        suspensionService.suspend(STUDY, "S2", "INC-2", "第二次",
                null, T0.plus(Duration.ofDays(5)));
        suspensionService.resume(STUDY, "R1", "S1", 1, T0.plus(Duration.ofDays(6)));

        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(7)), DenialReason.STUDY_SUSPENDED);

        // 再恢复 S2 后才放行
        suspensionService.resume(STUDY, "R2", "S2", 1, T0.plus(Duration.ofDays(8)));
        assertThat(activityService.record(STUDY, P, "INTERVIEW", "A9",
                T0.plus(Duration.ofDays(9)))).isNotNull();
    }

    @Test
    void resumeNeverReauthorizesAWithdrawnParticipant() {
        suspensionService.suspend(STUDY, "S1", "INC-1", "原因",
                null, T0.plus(Duration.ofDays(3)));
        // 暂停期间撤回同意
        consentService.withdraw(STUDY, P, "W1", T0.plus(Duration.ofDays(4)));
        suspensionService.resume(STUDY, "R1", "S1", 1, T0.plus(Duration.ofDays(5)));

        // 恢复不使撤回者重新获得授权
        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(6)), DenialReason.CONSENT_WITHDRAWN);

        // 重新签署后才恢复
        consentService.sign(STUDY, P, "G2", null,
                Set.of("INTERVIEW"), T0.plus(Duration.ofDays(7)));
        assertThat(activityService.record(STUDY, P, "INTERVIEW", "A9",
                T0.plus(Duration.ofDays(8)))).isNotNull();
    }

    // ---------- 规则 4：幂等/冲突与时间线 ----------

    @Test
    void suspensionAndResumeAreIdempotentButConflictOnDifferentContent() {
        Instant suspendAt = T0.plus(Duration.ofDays(3));
        SuspensionDecision s1 = suspensionService.suspend(STUDY, "SUS-1", "INC-42",
                "原因", Set.of("BLOOD"), suspendAt);
        // 同号同内容重放 → 原结果
        SuspensionDecision s1Again = suspensionService.suspend(STUDY, "SUS-1", "INC-42",
                "原因", Set.of("BLOOD"), suspendAt);
        assertThat(s1Again.getId()).isEqualTo(s1.getId());

        // 同号异内容 → 冲突
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-1", "INC-42",
                "不同原因", Set.of("BLOOD"), suspendAt))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> suspensionService.suspend(STUDY, "SUS-1", "INC-42",
                "原因", Set.of("SURVEY"), suspendAt))
                .isInstanceOf(ConflictException.class);

        SuspensionDecision r1 = suspensionService.resume(STUDY, "R1", "SUS-1", 1,
                T0.plus(Duration.ofDays(5)));
        SuspensionDecision r1Again = suspensionService.resume(STUDY, "R1", "SUS-1", 1,
                T0.plus(Duration.ofDays(5)));
        assertThat(r1Again.getId()).isEqualTo(r1.getId());

        // 恢复同号异内容 → 冲突
        assertThatThrownBy(() -> suspensionService.resume(STUDY, "R1", "SUS-1", 2,
                T0.plus(Duration.ofDays(5))))
                .isInstanceOf(ConflictException.class);

        // 暂停号与恢复号不能互用
        assertThatThrownBy(() -> suspensionService.resume(STUDY, "SUS-1", "SUS-1", 1,
                T0.plus(Duration.ofDays(6))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void decisionsAppearOnImmutableTimelineInOrder() {
        suspensionService.suspend(STUDY, "S1", "INC-1", "第一次暂停",
                null, T0.plus(Duration.ofDays(3)));
        suspensionService.resume(STUDY, "R1", "S1", 1, T0.plus(Duration.ofDays(5)));

        List<AuthorizationQueryService.TimelineEntry> timeline = queryService.timeline(STUDY, P);
        var suspensions = timeline.stream()
                .filter(e -> e instanceof AuthorizationQueryService.TimelineEntry.Suspension)
                .map(e -> (AuthorizationQueryService.TimelineEntry.Suspension) e)
                .toList();
        assertThat(suspensions).hasSize(2);
        assertThat(suspensions.get(0).decisionType()).isEqualTo("SUSPEND");
        assertThat(suspensions.get(0).externalIncidentId()).isEqualTo("INC-1");
        assertThat(suspensions.get(1).decisionType()).isEqualTo("RESUME");
        assertThat(suspensions.get(1).suspendEventId()).isEqualTo("S1");
    }

    @Test
    void statusSnapshotShowsSuspensionDenial() {
        suspensionService.suspend(STUDY, "S1", "INC-1", "原因",
                Set.of("BLOOD"), T0.plus(Duration.ofDays(3)));
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
