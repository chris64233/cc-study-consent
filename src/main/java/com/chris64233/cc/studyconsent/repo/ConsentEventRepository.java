package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentEventRepository extends JpaRepository<ConsentEvent, Long> {

    Optional<ConsentEvent> findByExternalEventId(String externalEventId);

    /** 事件按业务时间升序；同一时刻按入库 id 决胜，保证时间线确定。 */
    List<ConsentEvent> findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(
            Long studyId, Long participantId);
}
