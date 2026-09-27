package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.error.BusinessConflictException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestClockConfig.class)
class ConsentSignTest {

    @Autowired
    StudyService studyService;

    @Autowired
    ConsentService consentService;

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
    }

    @Test
    void signRecordsExternalEventIdAndSignTime() {
        ConsentService.ConsentResult result = consentService.sign(code, participant,
                evt("EVT-1"), Set.of("SURVEY"));

        assertThat(result.replayed()).isFalse();
        ConsentEvent event = result.event();
        assertThat(event.getExternalEventId()).isEqualTo(evt("EVT-1"));
        assertThat(event.getSignedAt()).isEqualTo(TestClockConfig.T0);
        assertThat(event.getProtocolVersion().getVersionNumber()).isEqualTo(1);
        assertThat(event.getSelectedActivityTypes()).containsExactlyInAnyOrder("SURVEY");
    }

    @Test
    void signIsAlwaysAgainstCurrentVersion() {
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY"));
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"), ChangeType.NON_SUBSTANTIVE);

        ConsentService.ConsentResult result = consentService.sign(code, participant,
                evt("EVT-2"), Set.of("BLOOD_DRAW"));
        assertThat(result.event().getProtocolVersion().getVersionNumber()).isEqualTo(2);
    }

    @Test
    void selectionCannotExceedAllowedScope() {
        assertThatThrownBy(() -> consentService.sign(code, participant, evt("EVT-1"),
                Set.of("SURVEY", "GENETIC_TEST")))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("超出");
    }

    @Test
    void sameEventSameContentIsIdempotent() {
        ConsentService.ConsentResult first = consentService.sign(code, participant,
                evt("EVT-1"), Set.of("SURVEY"));
        ConsentService.ConsentResult second = consentService.sign(code, participant,
                evt("EVT-1"), Set.of("SURVEY"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.event().getId()).isEqualTo(first.event().getId());
    }

    @Test
    void sameEventDifferentContentConflicts() {
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY"));

        assertThatThrownBy(() -> consentService.sign(code, participant, evt("EVT-1"),
                Set.of("BLOOD_DRAW")))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> consentService.sign(code, "P-OTHER", evt("EVT-1"),
                Set.of("SURVEY")))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void withdrawalIsIdempotentAndConflictChecked() {
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY"));
        Instant effectiveAt = TestClockConfig.T0.plusSeconds(3600);

        ConsentService.ConsentResult first = consentService.withdraw(code, participant,
                evt("EVT-W"), effectiveAt);
        ConsentService.ConsentResult second = consentService.withdraw(code, participant,
                evt("EVT-W"), effectiveAt);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.event().getId()).isEqualTo(first.event().getId());

        assertThatThrownBy(() -> consentService.withdraw(code, participant, evt("EVT-W"),
                effectiveAt.plusSeconds(60)))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void cannotSignBeforeAnyVersionPublished() {
        String emptyStudy = "S-" + UUID.randomUUID();
        studyService.createStudy(emptyStudy, "无版本研究");
        assertThatThrownBy(() -> consentService.sign(emptyStudy, participant, evt("EVT-1"),
                Set.of("SURVEY")))
                .isInstanceOf(BusinessValidationException.class);
    }
}
