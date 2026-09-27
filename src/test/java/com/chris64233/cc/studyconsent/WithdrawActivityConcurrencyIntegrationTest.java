package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.service.ActivityService;
import com.chris64233.cc.studyconsent.service.ConsentService;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则 4：撤回与活动记录创建并发时，不能产生"撤回生效后仍被接受的活动"。
 */
class WithdrawActivityConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private MutableClock clock;

    private static final String STUDY = "STUDY-X";
    private static final String P = "P-X";
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant SIGN_AT = T0.plusSeconds(3600);
    /** 撤回生效时间与活动发生时间相同，最大化竞态。 */
    private static final Instant T = T0.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        clock.set(T0);
        studyService.createStudy(STUDY, "并发研究");
        studyService.createParticipant(P, "赵六");
        studyService.publishVersion(STUDY, ChangeType.INITIAL, Set.of("INTERVIEW"));
        consentService.sign(STUDY, P, "G1", null, Set.of("INTERVIEW"), SIGN_AT);
    }

    /**
     * 串行语义：撤回会否定所有"发生时间 ≥ 撤回时间"的活动授权，因此只要存在
     * 这样的已接受活动，撤回就必须拒绝（无论撤回时间等于还是早于该活动）。
     */
    @Test
    void withdrawAtOrBeforeAnExistingActivityIsRejected() {
        activityService.record(STUDY, P, "INTERVIEW", "A1", T);
        // 同刻撤回 → 拒绝
        assertThatThrownBy(() -> consentService.withdraw(STUDY, P, "W1", T))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("撤回");
        // 撤回时间早于已接受活动 → 同样拒绝，否则会追溯否定 A1 的授权
        assertThatThrownBy(() -> consentService.withdraw(STUDY, P, "W2", T.minusSeconds(1)))
                .isInstanceOf(ConflictException.class);
        // 撤回时间严格晚于所有活动 → 允许
        consentService.withdraw(STUDY, P, "W3", T.plusSeconds(1));
        // 撤回之后的活动 → 拒绝
        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW", "A2", T.plusSeconds(2)))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }

    /** 串行语义：T 撤回后，T 的活动被拒绝；但撤回前的活动仍有效。 */
    @Test
    void activityAfterWithdrawIsRejectedButPriorActivityStands() {
        activityService.record(STUDY, P, "INTERVIEW", "A0", T.minusSeconds(10));
        consentService.withdraw(STUDY, P, "W1", T);

        assertThatThrownBy(() -> activityService.record(STUDY, P, "INTERVIEW", "A1", T))
                .isInstanceOf(ActivityNotAuthorizedException.class);
    }

    /**
     * 并发对决：同一参与者、同一时间点 T，一个线程撤回、一个线程记录活动。
     * 无论谁先拿到参与者行锁，结果必须自洽：
     * 绝不能同时出现"撤回在 T 生效"和"发生于 T 的活动被接受"。
     * 重复执行以提高竞态覆盖。
     */
    @RepeatedTest(20)
    void concurrentWithdrawAndActivityNeverLeavesInconsistentState() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> withdrawOutcome = new AtomicReference<>();
        AtomicReference<String> activityOutcome = new AtomicReference<>();

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

        withdrawer.start();
        actor.start();
        withdrawer.join();
        actor.join();

        String w = withdrawOutcome.get();
        String a = activityOutcome.get();

        // 不允许出现非预期异常
        assertThat(w).isIn("OK", "REJECTED");
        assertThat(a).isIn("ACCEPTED", "REFUSED");

        // 核心不变量：撤回在 T 生效 与 活动在 T 被接受 不可同时成立
        boolean withdrawEffectiveAtT = "OK".equals(w);
        boolean activityAcceptedAtT = "ACCEPTED".equals(a);
        assertThat(withdrawEffectiveAtT && activityAcceptedAtT)
                .as("撤回生效与活动接受互斥：withdraw=%s activity=%s", w, a)
                .isFalse();
    }
}
