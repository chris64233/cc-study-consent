package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.DecisionType;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyDecisionRepository extends JpaRepository<StudyDecision, Long> {

    Optional<StudyDecision> findByExternalEventId(String externalEventId);

    /** 研究的全部暂停/恢复决定，按生效时间升序（同刻按入库 id 决胜）。 */
    List<StudyDecision> findByStudyIdOrderByEffectiveAtAscIdAsc(Long studyId);

    List<StudyDecision> findByStudyIdAndDecisionTypeOrderByEffectiveAtAscIdAsc(
            Long studyId, DecisionType decisionType);
}
