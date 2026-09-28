package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
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
 * 规则 3：暂停 / 恢复与撤回、活动登记并发时，
 * 判断必须基于研究与参与者的当前状态，且结果自洽。
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
    /** 暂停生效 / 活动发生 / 撤回生效 的同一时刻，最大化竞态。 */
    private static final Instant T = T0.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        clock.set(T0);
        studyService.createStudy(STUDY, "并发暂停研究");
        studyService.createParticipant(P, "参与者K");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW"));
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), SIGN_AT);
    }

    /**
     * 并发对决 1：一个线程在 T 暂停整个研究，另一个线程登记发生于 T 的活动。
     * 统一锁序（研究 → 参与者）使两者串行：
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
                suspensionService.suspend(STUDY, "S-" + System.nanoTime(),
                        SuspensionScopeType.STUDY, null, "安全事件", T);
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

        boolean suspendedAtT = "OK".equals(s);
        boolean activityAcceptedAtT = "ACCEPTED".equals(a);
        assertThat(suspendedAtT && activityAcceptedAtT)
                .as("暂停生效与活动接受互斥：suspend=%s activity=%s", s, a)
                .isFalse();
    }

    /**
     * 并发对决 2：暂停已在 T-10 生效；一个线程在 T 提交恢复确认，
     * 另一个线程登记发生于 T 的活动。恢复生效与活动被接受可以同时成立
     * （恢复后活动本就允许），但绝不能出现"恢复未生效却接受活动"以外的矛盾——
     * 这里验证串行后两种自洽结局之一：活动要么被暂停拒绝、要么在恢复后被接受。
     */
    @RepeatedTest(20)
    void concurrentResumeAndActivityIsSerializable() throws Exception {
        suspensionService.suspend(STUDY, "SUSP-1", SuspensionScopeType.STUDY,
                null, "安全事件", T.minusSeconds(10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> resumeOutcome = new AtomicReference<>("ABSENT");
        AtomicReference<String> activityOutcome = new AtomicReference<>();

        Thread resumer = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.resume(STUDY, P, "R-" + System.nanoTime(),
                        "SUSP-1", 1, T);
                resumeOutcome.set("OK");
            } catch (Exception e) {
                resumeOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });
        Thread actor = new Thread(() -> {
            try {
                barrier.await();
                activityService.record(STUDY, P, "INTERVIEW", "A-" + System.nanoTime(), T);
                activityOutcome.set("ACCEPTED");
            } catch (ActivityNotAuthorizedException e) {
                activityOutcome.set("REFUSED");
            } catch (Exception e) {
                activityOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });

        resumer.start();
        actor.start();
        resumer.join();
        actor.join();

        assertThat(resumeOutcome.get()).isEqualTo("OK");
        // 活动被接受 ⟺ 恢复先生效；若活动先到则暂停拒绝。两者皆自洽，不允许其他异常
        assertThat(activityOutcome.get()).isIn("ACCEPTED", "REFUSED");
    }

    /**
     * 并发对决 3：参与者已在 T-10 被暂停。一个线程在 T 恢复，另一个线程在 T 撤回。
     * 串行后无论先后：若撤回先生效，恢复因"同意已撤回"失败；若恢复先生效，
     * 撤回仍成立。两种结局下参与者在 T 之后都不得持有有效授权。
     */
    @RepeatedTest(20)
    void concurrentResumeAndWithdrawNeverReauthorizesWithdrawnParticipant() throws Exception {
        suspensionService.suspend(STUDY, "SUSP-2", SuspensionScopeType.STUDY,
                null, "安全事件", T.minusSeconds(10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> resumeOutcome = new AtomicReference<>("ABSENT");
        AtomicReference<String> withdrawOutcome = new AtomicReference<>("ABSENT");

        Thread resumer = new Thread(() -> {
            try {
                barrier.await();
                suspensionService.resume(STUDY, P, "R-" + System.nanoTime(),
                        "SUSP-2", 1, T);
                resumeOutcome.set("OK");
            } catch (ConflictException e) {
                resumeOutcome.set("REJECTED");
            } catch (Exception e) {
                resumeOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });
        Thread withdrawer = new Thread(() -> {
            try {
                barrier.await();
                consentService.withdraw(STUDY, P, "W-" + System.nanoTime(), T);
                withdrawOutcome.set("OK");
            } catch (ConflictException e) {
                withdrawOutcome.set("REJECTED");
            } catch (Exception e) {
                withdrawOutcome.set("ERROR:" + e.getClass().getSimpleName());
            }
        });

        resumer.start();
        withdrawer.start();
        resumer.join();
        withdrawer.join();

        String r = resumeOutcome.get();
        String w = withdrawOutcome.get();
        // 该场景下没有活动记录，撤回不会被追溯规则拒绝
        assertThat(w).isEqualTo("OK");
        assertThat(r).isIn("OK", "REJECTED");
        // 若恢复被拒，必是因为撤回先生效（恢复看到了撤回）
        if ("REJECTED".equals(r)) {
            assertThat(w).isEqualTo("OK");
        }

        // 核心不变量：两种串行结局下，T 之后参与者都不持有有效授权——
        // 撤回先生效则暂停仍开启（STUDY_SUSPENDED）；恢复先生效则同意已撤回（CONSENT_WITHDRAWN）
        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW", "A-FINAL",
                        T.plusSeconds(1)))
                .isInstanceOf(ActivityNotAuthorizedException.class)
                .satisfies(ex -> {
                    var reason = ((ActivityNotAuthorizedException) ex).getRefusal().reason();
                    assertThat(reason).isIn(
                            com.chris64233.cc.studyconsent.domain.DenialReason.STUDY_SUSPENDED,
                            com.chris64233.cc.studyconsent.domain.DenialReason.CONSENT_WITHDRAWN);
                });
    }

    @Test
    void suspendingAfterResumedSuspensionThenNewResumeClosesOnlyNewOne() {
        suspensionService.suspend(STUDY, "S1", SuspensionScopeType.STUDY, null,
                "事件1", T0.plusSeconds(3600));
        suspensionService.resume(STUDY, P, "R1", "S1", 1, T0.plusSeconds(4000));
        // 新暂停生效后，旧恢复重放（幂等）不会影响新暂停
        suspensionService.suspend(STUDY, "S2", SuspensionScopeType.STUDY, null,
                "事件2", T0.plusSeconds(5000));
        var replay = suspensionService.resume(STUDY, P, "R1", "S1", 1, T0.plusSeconds(4000));
        assertThat(replay.getExternalEventId()).isEqualTo("R1");

        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW", "A1",
                        T0.plusSeconds(5100)))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }
}
