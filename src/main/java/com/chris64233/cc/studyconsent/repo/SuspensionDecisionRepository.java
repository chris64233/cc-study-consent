package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.SuspensionDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SuspensionDecisionRepository extends JpaRepository<SuspensionDecision, Long> {

    Optional<SuspensionDecision> findByExternalEventId(String externalEventId);

    /** 某研究的全部暂停/恢复决定，按生效时间升序、同刻按入库 id 决胜。 */
    List<SuspensionDecision> findByStudyIdOrderByEffectiveAtAscIdAsc(Long studyId);

    /** 指定时间点（含）之前已生效的暂停/恢复决定，按生效时间升序、同刻按入库 id 决胜。 */
    List<SuspensionDecision> findByStudyIdAndEffectiveAtLessThanEqualOrderByEffectiveAtAscIdAsc(
            Long studyId, Instant at);
}
