package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.error.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 撤回与活动记录的授权规则及并发安全（需求 4）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class WithdrawalAndActivityTest {

    @Autowired
    StudyService studyService;

    @Autowired
    ConsentService consentService;

    @Autowired
    ActivityService activityService;

    @Autowired
    MutableClock clock;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private String code;
    private final String participant = "P-1";

    /** 外部事件号全局唯一，按研究编号隔离避免测试间冲突。 */
    private String evt(String id) {
        return id + "-" + code;
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        clock.setInstant(TestClockConfig.T0);
        code = "S-" + UUID.randomUUID();
        studyService.createStudy(code, "测试研究");
        studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"), null);
        consentService.sign(code, participant, evt("EVT-1"), Set.of("SURVEY", "BLOOD_DRAW"));
    }

    @Test
    void activityWithValidAuthorizationIsRecorded() {
        ActivityRecord record = activityService.record(code, participant, "SURVEY",
                TestClockConfig.T0.plusSeconds(60));

        assertThat(record.getId()).isNotNull();
        assertThat(record.getAuthorizedBy().getExternalEventId()).isEqualTo(evt("EVT-1"));
        assertThat(record.getRecordedAt()).isEqualTo(TestClockConfig.T0);
    }

    @Test
    void activityWithoutConsentIsRejected() {
        assertThatThrownBy(() -> activityService.record(code, "P-UNKNOWN", "SURVEY",
                TestClockConfig.T0))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }

    @Test
    void activityBeforeSignIsRejected() {
        assertThatThrownBy(() -> activityService.record(code, participant, "SURVEY",
                TestClockConfig.T0.minusSeconds(3600)))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .hasMessageContaining("没有有效的同意");
    }

    @Test
    void activityAtOrAfterWithdrawalEffectiveTimeIsRejected() {
        Instant withdrawAt = TestClockConfig.T0.plusSeconds(3600);
        consentService.withdraw(code, participant, evt("EVT-W"), withdrawAt);

        // 生效时间之前发生的活动仍被接受
        ActivityRecord before = activityService.record(code, participant, "SURVEY",
                withdrawAt.minusSeconds(1));
        assertThat(before.getId()).isNotNull();

        // 生效时间点及之后发生的活动被拒绝
        assertThatThrownBy(() -> activityService.record(code, participant, "SURVEY", withdrawAt))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .hasMessageContaining("撤回");
        assertThatThrownBy(() -> activityService.record(code, participant, "SURVEY",
                withdrawAt.plusSeconds(3600)))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }

    @Test
    void reSignAfterWithdrawalRestoresAuthorization() {
        Instant withdrawAt = TestClockConfig.T0.plusSeconds(3600);
        consentService.withdraw(code, participant, evt("EVT-W"), withdrawAt);
        clock.setInstant(withdrawAt.plusSeconds(60));
        consentService.sign(code, participant, evt("EVT-2"), Set.of("SURVEY"));

        ActivityRecord record = activityService.record(code, participant, "SURVEY",
                withdrawAt.plusSeconds(120));
        assertThat(record.getAuthorizedBy().getExternalEventId()).isEqualTo(evt("EVT-2"));
    }

    /**
     * 并发：撤回事务先获得锁并写入（尚未提交），活动记录事务阻塞在同一锁上；
     * 撤回提交后，活动记录必须观察到撤回并拒绝生效时间之后的活动，
     * 不会产生“撤回生效后仍被接受”的活动。
     */
    @Test
    void concurrentWithdrawalAndActivityAreSerialized() throws Exception {
        Instant withdrawAt = TestClockConfig.T0.plusSeconds(60);
        Instant activityAt = withdrawAt.plusSeconds(60);

        CountDownLatch withdrawalWritten = new CountDownLatch(1);
        CountDownLatch releaseWithdrawal = new CountDownLatch(1);
        AtomicReference<Throwable> withdrawalError = new AtomicReference<>();
        AtomicReference<Throwable> activityError = new AtomicReference<>();

        Thread withdrawalThread = new Thread(() -> {
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    consentService.withdraw(code, participant, evt("EVT-W"), withdrawAt);
                    withdrawalWritten.countDown();
                    await(releaseWithdrawal);
                });
            } catch (Throwable t) {
                withdrawalError.set(t);
            }
        });
        withdrawalThread.start();
        assertThat(withdrawalWritten.await(5, TimeUnit.SECONDS)).isTrue();

        Thread activityThread = new Thread(() -> {
            try {
                activityService.record(code, participant, "SURVEY", activityAt);
            } catch (Throwable t) {
                activityError.set(t);
            }
        });
        activityThread.start();

        // 给活动线程时间阻塞在登记行悲观锁上，然后提交撤回
        Thread.sleep(500);
        releaseWithdrawal.countDown();

        withdrawalThread.join(10_000);
        activityThread.join(10_000);

        assertThat(withdrawalError.get()).isNull();
        assertThat(activityError.get())
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .hasMessageContaining("撤回");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
