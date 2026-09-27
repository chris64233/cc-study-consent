package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import com.chris64233.cc.studyconsent.service.StudyService;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则 1：方案版本严格递增、声明活动类型、标记变更类型、已发布不可修改、
 * 同一研究同时只有一个当前版本。
 */
class VersionPublishingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudyService studyService;
    @Autowired
    private StudyVersionRepository versionRepository;
    @Autowired
    private MutableClock clock;

    private final String studyCode = "STUDY-V";
    private final String participantCode = "P-V";

    @BeforeEach
    void setUp() {
        studyService.createStudy(studyCode, "版本研究");
        studyService.createParticipant(participantCode, "张三");
    }

    @Test
    void firstVersionMustBeInitialAndLaterVersionsMustDeclareChangeType() {
        StudyVersion v1 = studyService.publishVersion(
                studyCode, ChangeType.INITIAL, Set.of("INTERVIEW"));
        assertThat(v1.getVersionNo()).isEqualTo(1);

        assertThatThrownBy(() -> studyService.publishVersion(
                studyCode, ChangeType.INITIAL, Set.of("INTERVIEW")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("实质变更");

        StudyVersion v2 = studyService.publishVersion(
                studyCode, ChangeType.NON_SUBSTANTIVE, Set.of("INTERVIEW", "SURVEY"));
        assertThat(v2.getVersionNo()).isEqualTo(2);
        assertThat(studyService.getStudy(studyCode).getCurrentVersion()).isEqualTo(2);
    }

    @Test
    void versionMustDeclareAtLeastOneActivity() {
        assertThatThrownBy(() -> studyService.publishVersion(
                studyCode, ChangeType.INITIAL, Set.of()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void publishedVersionIsImmutableSnapshot() {
        studyService.publishVersion(studyCode, ChangeType.INITIAL, Set.of("INTERVIEW", "BLOOD"));
        StudyVersion v1 = versionRepository
                .findByStudyIdAndVersionNo(studyService.getStudy(studyCode).getId(), 1).orElseThrow();

        // 发布内容不同的新版本后，旧版本快照不被覆盖
        studyService.publishVersion(studyCode, ChangeType.NON_SUBSTANTIVE, Set.of("SURVEY"));

        StudyVersion v1Reloaded = versionRepository.findById(v1.getId()).orElseThrow();
        assertThat(v1Reloaded.getVersionNo()).isEqualTo(1);
        assertThat(v1Reloaded.getAllowedActivityTypes()).containsExactlyInAnyOrder("INTERVIEW", "BLOOD");
        // 快照集合不可变
        assertThatThrownBy(() -> v1Reloaded.getAllowedActivityTypes().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentPublishesProduceStrictlyIncrementingVersions() throws Exception {
        studyService.publishVersion(studyCode, ChangeType.INITIAL, Set.of("INTERVIEW"));

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool[i] = new Thread(() -> {
                try {
                    barrier.await();
                    studyService.publishVersion(studyCode, ChangeType.NON_SUBSTANTIVE,
                            Set.of("INTERVIEW", "ACT-" + idx));
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                }
            });
            pool[i].start();
        }
        for (Thread t : pool) {
            t.join();
        }

        // 行级写锁串行化发布：4 个并发请求全部成功，版本号 2..5 无重复无缺口
        assertThat(success.get()).isEqualTo(4);
        assertThat(failure.get()).isZero();
        assertThat(studyService.getStudy(studyCode).getCurrentVersion()).isEqualTo(5);
        assertThat(versionRepository.findByStudyIdOrderByVersionNoAsc(
                studyService.getStudy(studyCode).getId()))
                .extracting(StudyVersion::getVersionNo)
                .containsExactly(1, 2, 3, 4, 5);
    }
}
