package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.AuthorizationEvaluator;
import com.chris64233.cc.studyconsent.service.AuthorizationQueryService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则 5：参与者授权状态、完整时间线查询，以及活动允许/拒绝的解释。
 */
class TimelineAndStatusQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private AuthorizationQueryService queryService;
    @Autowired
    private MutableClock clock;

    private static final String STUDY = "STUDY-Q";
    private static final String P = "P-Q";
    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");

    @BeforeEach
    void scenario() {
        clock.set(T0);
        studyService.createStudy(STUDY, "时间线研究");
        studyService.createParticipant(P, "孙七");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW", "BLOOD"));

        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), T0.plus(Duration.ofDays(1)));
        activityService.record(STUDY, P, "INTERVIEW", "A1", T0.plus(Duration.ofDays(2)));

        clock.set(T0.plus(Duration.ofDays(10)));
        studyService.publishVersion(STUDY, ChangeType.SUBSTANTIVE, Set.of("INTERVIEW", "MRI"));

        consentService.sign(STUDY, P, "G2", null, Set.of("INTERVIEW", "MRI"), T0.plus(Duration.ofDays(11)));
        activityService.record(STUDY, P, "MRI", "A2", T0.plus(Duration.ofDays(12)));
    }

    @Test
    void timelineContainsAllEventsInChronologicalOrder() {
        List<AuthorizationQueryService.TimelineEntry> timeline = queryService.timeline(STUDY, P);

        assertThat(timeline).hasSize(6);
        assertThat(timeline.get(0)).isInstanceOf(AuthorizationQueryService.TimelineEntry.VersionPublished.class);
        assertThat(timeline.get(1)).isInstanceOf(AuthorizationQueryService.TimelineEntry.Consent.class);
        assertThat(timeline.get(2)).isInstanceOf(AuthorizationQueryService.TimelineEntry.Activity.class);
        assertThat(timeline.get(3)).isInstanceOf(AuthorizationQueryService.TimelineEntry.VersionPublished.class);
        assertThat(timeline.get(4)).isInstanceOf(AuthorizationQueryService.TimelineEntry.Consent.class);
        assertThat(timeline.get(5)).isInstanceOf(AuthorizationQueryService.TimelineEntry.Activity.class);

        // 活动条目解释为什么被允许
        var firstActivity = (AuthorizationQueryService.TimelineEntry.Activity) timeline.get(2);
        assertThat(firstActivity.allowed()).isTrue();
        assertThat(firstActivity.effectiveVersionNo()).isEqualTo(1);
        assertThat(firstActivity.explanation()).contains("id=");
    }

    @Test
    void statusReportsPerActivityAuthorizationAtCurrentTime() {
        clock.set(T0.plus(Duration.ofDays(13)));
        AuthorizationQueryService.AuthorizationSnapshot snapshot = queryService.status(STUDY, P);

        assertThat(snapshot.currentVersionNo()).isEqualTo(2);
        assertThat(snapshot.latestConsentEventType()).isEqualTo("GRANT");
        assertThat(snapshot.latestConsentVersionNo()).isEqualTo(2);
        assertThat(snapshot.activityStatuses()).hasSize(2);

        var byType = snapshot.activityStatuses().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AuthorizationQueryService.ActivityStatus::activityType, s -> s));
        assertThat(byType.get("INTERVIEW").allowed()).isTrue();
        assertThat(byType.get("MRI").allowed()).isTrue();
    }

    @Test
    void statusShowsRefusalAfterWithdraw() {
        consentService.withdraw(STUDY, P, "W1", T0.plus(Duration.ofDays(13)));
        clock.set(T0.plus(Duration.ofDays(14)));

        AuthorizationQueryService.AuthorizationSnapshot snapshot = queryService.status(STUDY, P);
        assertThat(snapshot.latestConsentEventType()).isEqualTo("WITHDRAW");
        assertThat(snapshot.activityStatuses())
                .allMatch(s -> !s.allowed())
                .anyMatch(s -> "CONSENT_WITHDRAWN".equals(s.denialReason()));
    }

    @Test
    void explainTellsWhyAnActivityIsAllowed() {
        AuthorizationEvaluator.Result result = queryService.explain(
                STUDY, P, "MRI", T0.plus(Duration.ofDays(12)));
        assertThat(result).isInstanceOf(AuthorizationEvaluator.Result.Allowed.class);
        AuthorizationEvaluator.Result.Allowed allowed = (AuthorizationEvaluator.Result.Allowed) result;
        assertThat(allowed.effectiveVersionNo()).isEqualTo(2);
        assertThat(allowed.message()).contains("MRI");
    }

    @Test
    void explainTellsWhyAnActivityIsRefused() {
        // v2 实质变更发布后、参与者重新签署前：旧同意失效
        AuthorizationEvaluator.Result afterSubstantive = queryService.explain(
                STUDY, P, "INTERVIEW", T0.plus(Duration.ofDays(10)).plusSeconds(60));
        assertThat(afterSubstantive).isInstanceOf(AuthorizationEvaluator.Result.Refused.class);
        AuthorizationEvaluator.Result.Refused refused =
                (AuthorizationEvaluator.Result.Refused) afterSubstantive;
        assertThat(refused.reason()).isEqualTo(DenialReason.SUBSTANTIVE_VERSION_PUBLISHED);
        assertThat(refused.message()).contains("实质变更");
    }
}
