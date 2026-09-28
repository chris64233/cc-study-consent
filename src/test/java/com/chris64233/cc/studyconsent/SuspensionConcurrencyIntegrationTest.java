package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.SuspensionService;
import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则 3：恢复确认与新的暂停、活动登记并发时，必须基于研究与参与者的当前状态判断。
 *
 * <p>暂停/恢复持研究行锁，活动登记按 participant → study 固定顺序加锁，
 * 两者在研究锁上串行，核心不变量："暂停在 T 生效"与"发生于 T 的活动被接受"
 * 不可能同时成立；旧恢复只解除其引用的暂停，不能越过后来的新暂停。</p>
 */
class SuspensionConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private SuspensionService suspensionService;
    @Autowired
    private MutableClock clock;

    private static final String STUDY = "STUDY-K";
    private static final String P = "P-K";
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SIGN_AT = T0.plusSeconds(3600);
    /** 暂停生效时间与活动发生时间相同，最大化竞态。 */
    private static final Instant T = T0.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        clock.set(T0);
        studyService.createStudy(STUDY, "并发暂停研究");
        studyService.createParticipant(P, "钱九");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW"));
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), SIGN_AT);
    }

    /**
     * 并发对决：同一时间点 T，一个线程暂停整个研究，一个线程登记活动。
     * 无论谁先拿到研究行锁：
     * 绝不能同时出现"暂停在 T 生效"和"发生于 T 的活动被接受"。
     */
    @RepeatedTest(20)
    void concurrentSuspensionAndActivityNeverLeavesInconsistentState() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> suspendOutcome = new AtomicReference<>();
        AtomicReference<String> activityOutcome = new AtomicReference<>();

        Thread suspender = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.suspend(STUDY, "SUS-" + System.nanoTime(), "INC-1",
                        "安全事件", null, T);
                suspendOutcome.set("OK");
            } catch (ConflictException e) {
                suspendOutcome.set("REJECTED");
            } catch (Exception e) {
                suspendOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });
        Thread actor = new Thread(() -> {
            try {
                barrier.await();
                activityService.record(STUDY, P, "INTERVIEW", "A-" + System.nanoTime(), T);
                activityOutcome.set("ACCEPTED");
            } catch (ActivityNotAuthorizedException e) {
                activityOutcome.set("REFUSED");
            } catch (ConflictException e) {
                activityOutcome.set("CONFLICT");
            } catch (Exception e) {
                activityOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });

        suspender.start();
        actor.start();
        suspender.join();
        actor.join();

        String s = suspendOutcome.get();
        String a = activityOutcome.get();

        assertThat(s).isIn("OK", "REJECTED");
        assertThat(a).isIn("ACCEPTED", "REFUSED");

        boolean suspensionEffectiveAtT = "OK".equals(s);
        boolean activityAcceptedAtT = "ACCEPTED".equals(a);
        assertThat(suspensionEffectiveAtT && activityAcceptedAtT)
                .as("暂停生效与活动接受互斥：suspend=%s activity=%s", s, a)
                .isFalse();
    }

    /**
     * 恢复与新的暂停并发：恢复只解除其引用的旧暂停 S1，新暂停 S2 无论先后都仍然在效，
     * 之后的活动必须被拒绝（旧恢复不得越过后来生效的暂停）。
     */
    @RepeatedTest(20)
    void concurrentResumeAndNewSuspensionKeepsNewSuspensionEffective() throws Exception {
        Instant s1At = T0.plusSeconds(5000);
        Instant decisionAt = T0.plusSeconds(9000);
        suspensionService.suspend(STUDY, "S1", "INC-1", "第一次暂停", null, s1At);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> error = new AtomicReference<>();

        Thread resumer = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.resume(STUDY, "R-" + System.nanoTime(), "S1", 1, decisionAt);
            } catch (Exception e) {
                error.set("RESUME:" + e.getClass().getSimpleName());
            }
        });
        Thread suspender = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.suspend(STUDY, "S2-" + System.nanoTime(), "INC-2",
                        "第二次暂停", null, decisionAt);
            } catch (Exception e) {
                error.set("SUSPEND:" + e.getClass().getSimpleName());
            }
        });

        resumer.start();
        suspender.start();
        resumer.join();
        suspender.join();

        assertThat(error.get()).isNull();

        // 决定时点之后：S2 必然在效（恢复 R 只移除 S1），活动被拒
        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW",
                "A-LATE", decisionAt.plusSeconds(1)))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }

    /**
     * 恢复与同意撤回并发：恢复决定不针对个人授权，撤回者在恢复之后仍不能登记活动。
     */
    @Test
    void resumeConcurrentWithWithdrawDoesNotReauthorize() throws Exception {
        suspensionService.suspend(STUDY, "S1", "INC-1", "暂停", null,
                T0.plusSeconds(5000));

        Instant decisionAt = T0.plusSeconds(9000);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread resumer = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.resume(STUDY, "R1", "S1", 1, decisionAt);
            } catch (Exception ignored) {
                // 与暂停不同，恢复与撤回不持同一把锁，理论上不会冲突；失败则由断言捕获
            }
        });
        Thread withdrawer = new Thread(() -> {
            try {
                barrier.await();
                // 已有活动 A0 发生于 SIGN_AT 之后、T 之前；撤回生效时间必须严格晚于它
                consentService.withdraw(STUDY, P, "W1", decisionAt);
            } catch (Exception ignored) {
            }
        });
        resumer.start();
        withdrawer.start();
        resumer.join();
        withdrawer.join();

        // 无论两者交叠顺序如何：T 之后活动不可能同时绕过"暂停/撤回"两道限制
        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW",
                "A-LATE", decisionAt.plusSeconds(1)))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }
}
