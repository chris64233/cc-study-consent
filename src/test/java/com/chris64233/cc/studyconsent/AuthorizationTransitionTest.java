package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.service.AuthorizationDecision;
import com.chris64233.cc.studyconsent.service.AuthorizationService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本演进下的授权迁移规则（需求 3）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class AuthorizationTransitionTest {

    @Autowired
    StudyService studyService;

    @Autowired
    ConsentService consentService;

    @Autowired
    AuthorizationService authorizationService;

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
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY", "BLOOD_DRAW"));
    }

    private AuthorizationDecision decide(String activityType, Instant at) {
        return authorizationService.evaluate(studyService.getStudy(code), participant, activityType, at);
    }

    @Test
    void nonSubstantiveVersionKeepsCommonActivitiesAuthorized() {
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY"), ChangeType.NON_SUBSTANTIVE);
        Instant after = clock.instant().plusSeconds(60);

        AuthorizationDecision common = decide("SURVEY", after);
        assertThat(common.allowed()).isTrue();
        assertThat(common.basedOnVersion()).isEqualTo(1);

        AuthorizationDecision removed = decide("BLOOD_DRAW", after);
        assertThat(removed.allowed()).isFalse();
        assertThat(removed.reason()).contains("共有范围");
    }

    @Test
    void activityBeforeNonSubstantivePublishStillUsesFullOldScope() {
        Instant before = clock.instant().plusSeconds(3600);
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY"), ChangeType.NON_SUBSTANTIVE);

        // 发生在新版本发布之前的活动，仍按当时有效的授权判定
        assertThat(decide("BLOOD_DRAW", before).allowed()).isTrue();
    }

    @Test
    void substantiveVersionInvalidatesOldConsent() {
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"), ChangeType.SUBSTANTIVE);
        Instant after = clock.instant().plusSeconds(60);

        AuthorizationDecision decision = decide("SURVEY", after);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("实质变更");
    }

    @Test
    void reSignOnCurrentVersionRestoresAuthorization() {
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY"), ChangeType.SUBSTANTIVE);
        clock.advance(Duration.ofDays(1));
        consentService.sign(code, participant, evt("EVT-2"), Set.of("SURVEY"));

        AuthorizationDecision decision = decide("SURVEY", clock.instant().plusSeconds(60));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.basedOnVersion()).isEqualTo(2);
    }

    @Test
    void chainOfNonSubstantiveVersionsRequiresIntersection() {
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW", "FITNESS"),
                ChangeType.NON_SUBSTANTIVE);
        clock.advance(Duration.ofDays(1));
        studyService.publishVersion(code, Set.of("SURVEY", "FITNESS"), ChangeType.NON_SUBSTANTIVE);
        Instant after = clock.instant().plusSeconds(60);

        // v1 同意对 SURVEY 仍有效（v1∩v2∩v3 共有），对 BLOOD_DRAW 在 v3 中已移除
        assertThat(decide("SURVEY", after).allowed()).isTrue();
        assertThat(decide("BLOOD_DRAW", after).allowed()).isFalse();
        // FITNESS 虽在各版本允许范围内，但参与者签署时未选择
        assertThat(decide("FITNESS", after).allowed()).isFalse();
    }
}
