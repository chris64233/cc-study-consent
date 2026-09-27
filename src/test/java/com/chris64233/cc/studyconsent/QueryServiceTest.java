package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.service.AuthorizationDecision;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.QueryService;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权状态与完整时间线查询（需求 5）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class QueryServiceTest {

    @Autowired
    StudyService studyService;

    @Autowired
    ConsentService consentService;

    @Autowired
    ActivityService activityService;

    @Autowired
    QueryService queryService;

    @Autowired
    MutableClock clock;

    private String code;
    private final String participant = "P-1";

    /** 外部事件号全局唯一，按研究编号隔离避免测试间冲突。 */
    private String evt(String id) {
        return id + "-" + code;
    }

    @BeforeEach
    void setUp() {
        clock.setInstant(TestClockConfig.T0);
        code = "S-" + UUID.randomUUID();
        studyService.createStudy(code, "测试研究");
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"), null);
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY"));
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"), ChangeType.NON_SUBSTANTIVE);
        clock.advance(Duration.ofDays(1));
        activityService.record(code, participant, "SURVEY", clock.instant());
    }

    @Test
    void authorizationStatusCoversCurrentVersionActivityTypes() {
        QueryService.AuthorizationStatus status = queryService.authorizationStatus(code, participant, null);

        assertThat(status.currentVersion()).isEqualTo(2);
        assertThat(status.evaluatedAt()).isEqualTo(clock.instant());
        assertThat(status.activities()).hasSize(2);

        QueryService.ActivityAuthorization survey = find(status, "SURVEY");
        assertThat(survey.allowed()).isTrue();
        assertThat(survey.basedOnVersion()).isEqualTo(1);

        QueryService.ActivityAuthorization blood = find(status, "BLOOD_DRAW");
        assertThat(blood.allowed()).isFalse();
        assertThat(blood.reason()).contains("不在参与者签署");
    }

    @Test
    void explainGivesReasonForAllowAndDeny() {
        AuthorizationDecision allowed = queryService.explain(code, participant, "SURVEY", null);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.reason()).contains(evt("EVT-1"));

        AuthorizationDecision denied = queryService.explain(code, participant, "BLOOD_DRAW", null);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isNotBlank();
    }

    @Test
    void timelineContainsAllEventsInOrder() {
        Instant withdrawAt = clock.instant().plusSeconds(3600);
        consentService.withdraw(code, participant, evt("EVT-W"), withdrawAt);

        List<QueryService.TimelineEntry> timeline = queryService.timeline(code, participant);

        assertThat(timeline).extracting(QueryService.TimelineEntry::kind)
                .containsExactly(
                        "VERSION_PUBLISHED",
                        "CONSENT_SIGNED",
                        "VERSION_PUBLISHED",
                        "ACTIVITY_RECORDED",
                        "CONSENT_WITHDRAWN");
        // 按时间升序
        assertThat(timeline).isSortedAccordingTo(
                java.util.Comparator.comparing(QueryService.TimelineEntry::timestamp));

        QueryService.TimelineEntry activity = timeline.get(3);
        assertThat(activity.details().get("authorizedByEventId")).isEqualTo(evt("EVT-1"));
    }

    private QueryService.ActivityAuthorization find(QueryService.AuthorizationStatus status, String type) {
        return status.activities().stream()
                .filter(a -> a.activityType().equals(type))
                .findFirst()
                .orElseThrow();
    }
}
