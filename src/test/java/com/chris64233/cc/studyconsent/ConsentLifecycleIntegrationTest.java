package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.AuthorizationEvaluator;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则 2/3：当前版本签署、活动选择范围、外部事件号幂等与冲突、
 * 非实质变更继续授权共有活动、实质变更必须重新签署。
 */
class ConsentLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private MutableClock clock;

    private static final String STUDY = "STUDY-C";
    private static final String P = "P-C";
    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        clock.set(T0);
        studyService.createStudy(STUDY, "同意研究");
        studyService.createParticipant(P, "李四");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW", "BLOOD"));
    }

    private void assertRefused(String activity, Instant at, DenialReason reason) {
        assertThatThrownBy(() -> activityService.record(STUDY, P, activity, "EVT-" + System.nanoTime(), at))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .satisfies(ex -> assertThat(((ActivityNotAuthorizedException) ex).getRefusal().reason())
                        .isEqualTo(reason));
    }

    @Test
    void activityBeforeAnyConsentIsRefused() {
        assertRefused("INTERVIEW", T0.plusSeconds(60), DenialReason.NEVER_CONSENTED);
    }

    @Test
    void cannotConsentBeforeAnyVersionIsPublished() {
        studyService.createStudy("EMPTY", "空研究");
        studyService.createParticipant("P-EMPTY", "王五");
        assertThatThrownBy(() -> consentService.sign("EMPTY", "P-EMPTY", "E1",
                null, Set.of("X"), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("尚未发布");
    }

    @Test
    void selectedActivitiesMustStayWithinVersionScope() {
        assertThatThrownBy(() -> consentService.sign(STUDY, P, "E1",
                null, Set.of("INTERVIEW", "NOT_ALLOWED"), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超出");
    }

    @Test
    void canOnlySignCurrentVersion() {
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));
        assertThatThrownBy(() -> consentService.sign(STUDY, P, "E1",
                1, Set.of("INTERVIEW"), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("当前版本");
    }

    @Test
    void sameEventWithSameContentIsIdempotent() {
        clock.advance(Duration.ofMinutes(1));
        Instant signedAt = clock.now();
        ConsentEvent first = consentService.sign(STUDY, P, "EVT-100",
                null, Set.of("INTERVIEW"), signedAt);
        // 再次以相同事件号、相同内容请求：返回同一事件，不新增记录
        ConsentEvent again = consentService.sign(STUDY, P, "EVT-100",
                1, Set.of("INTERVIEW"), signedAt);
        assertThat(again.getId()).isEqualTo(first.getId());
    }

    @Test
    void retryWithoutExplicitSignedAtIsIdempotent() {
        // 首次与重试都不带 signedAt（Clock 已推进），应幂等返回同一事件而非冲突
        clock.set(T0.plus(Duration.ofDays(1)));
        ConsentEvent first = consentService.sign(STUDY, P, "EVT-200", null, Set.of("INTERVIEW"), null);
        clock.advance(Duration.ofHours(3));
        ConsentEvent retry = consentService.sign(STUDY, P, "EVT-200", null, Set.of("INTERVIEW"), null);
        assertThat(retry.getId()).isEqualTo(first.getId());
    }

    @Test
    void sameEventWithDifferentContentConflicts() {
        clock.advance(Duration.ofMinutes(1));
        consentService.sign(STUDY, P, "EVT-101", null, Set.of("INTERVIEW"), clock.now());

        assertThatThrownBy(() -> consentService.sign(STUDY, P, "EVT-101",
                null, Set.of("BLOOD"), clock.now()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("内容不同");
    }

    @Test
    void nonSubstantiveVersionKeepsConsentEffectiveForSharedActivities() {
        Instant signTime = T0.plus(Duration.ofDays(1));
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW", "BLOOD"), signTime);

        // v2 非实质变更：新增 SURVEY，保留 INTERVIEW/BLOOD
        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "SURVEY"));

        Instant afterV2 = T0.plus(Duration.ofDays(11));
        // 共有活动继续被旧同意授权
        var record = activityService.record(STUDY, P, "INTERVIEW", "A1", afterV2);
        assertThat(record.getEffectiveVersionNo()).isEqualTo(2);
        // 新活动 SURVEY 未在旧同意中选择 → 拒绝
        assertRefused("SURVEY", afterV2, DenialReason.ACTIVITY_NOT_SELECTED);
    }

    @Test
    void activityRemovedByNonSubstantiveVersionIsNoLongerAuthorized() {
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW", "BLOOD"),
                T0.plus(Duration.ofDays(1)));

        // v2 非实质变更但移除了 BLOOD（非实质变更也可以收窄活动范围）
        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE, Set.of("INTERVIEW"));

        assertRefused("BLOOD", T0.plus(Duration.ofDays(11)),
                DenialReason.ACTIVITY_NOT_ALLOWED_IN_VERSION);
    }

    @Test
    void substantiveVersionInvalidatesOldConsentEntirely() {
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW", "BLOOD"),
                T0.plus(Duration.ofDays(1)));
        // 旧版本下的活动合法
        activityService.record(STUDY, P, "INTERVIEW", "A0", T0.plus(Duration.ofDays(2)));

        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD", "MRI"));

        Instant afterV2 = T0.plus(Duration.ofDays(11));
        // 即使是完全相同的活动，旧同意也不能再授权
        assertRefused("INTERVIEW", afterV2, DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);
        assertRefused("BLOOD", afterV2, DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);

        // 重新签署当前版本后恢复
        consentService.sign(STUDY, P, "G2", null, Set.of("INTERVIEW", "MRI"), afterV2.plusSeconds(60));
        var record = activityService.record(STUDY, P, "MRI", "A1", afterV2.plusSeconds(120));
        assertThat(record.getEffectiveVersionNo()).isEqualTo(2);
        // BLOOD 未在新同意中选择
        assertRefused("BLOOD", afterV2.plusSeconds(130), DenialReason.ACTIVITY_NOT_SELECTED);
    }

    @Test
    void substantiveAfterChainOfNonSubstantiveVersionsRequiresResign() {
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(1)));

        clock.set(T0.plus(Duration.ofDays(5)));
        studyService.publishVersion(STUDY, ChangeType.NON_SUBSTANTIVE,
                Set.of("INTERVIEW", "SURVEY"));
        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "SURVEY", "MRI"));

        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(11)),
                DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);
    }

    @Test
    void activityAuthorizationIsEvaluatedAtOccurrenceTime() {
        // v1 时期签署并活动
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(1)));
        activityService.record(STUDY, P, "INTERVIEW", "OLD", T0.plus(Duration.ofDays(2)));

        // v2 实质变更
        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE, Set.of("INTERVIEW"));

        // 发生在 v2 发布之前的活动（事后补录）仍应按当时状态授权
        var backdated = activityService.record(STUDY, P, "INTERVIEW", "BACKDATED",
                T0.plus(Duration.ofDays(3)));
        assertThat(backdated.getEffectiveVersionNo()).isEqualTo(1);

        // 但不能补录到签署之前
        assertRefused("INTERVIEW", T0.plus(Duration.ofHours(1)), DenialReason.NEVER_CONSENTED);
    }

    @Test
    void resigningAfterWithdrawRestoresAuthorization() {
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(1)));
        consentService.withdraw(STUDY, P, "W1", T0.plus(Duration.ofDays(2)));

        assertRefused("INTERVIEW", T0.plus(Duration.ofDays(3)), DenialReason.CONSENT_WITHDRAWN);

        consentService.sign(STUDY, P, "G2", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(4)));
        var record = activityService.record(STUDY, P, "INTERVIEW", "A9", T0.plus(Duration.ofDays(5)));
        assertThat(record).isNotNull();
    }

    @Test
    void withdrawIdempotentButConflictsOnDifferentTime() {
        var w = consentService.withdraw(STUDY, P, "W9", T0.plus(Duration.ofDays(2)));
        var wAgain = consentService.withdraw(STUDY, P, "W9", T0.plus(Duration.ofDays(2)));
        assertThat(wAgain.getId()).isEqualTo(w.getId());

        assertThatThrownBy(() -> consentService.withdraw(STUDY, P, "W9", T0.plus(Duration.ofDays(3))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void grantAndWithdrawCannotReuseExternalEventId() {
        consentService.sign(STUDY, P, "X1", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(1)));
        assertThatThrownBy(() -> consentService.withdraw(STUDY, P, "X1", T0.plus(Duration.ofDays(2))))
                .isInstanceOf(ConflictException.class);
    }
}
