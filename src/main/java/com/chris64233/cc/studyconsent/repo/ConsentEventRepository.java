package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.Study;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentEventRepository extends JpaRepository<ConsentEvent, Long> {

    Optional<ConsentEvent> findByExternalEventId(String externalEventId);

    List<ConsentEvent> findByStudyAndParticipantIdOrderByRecordedAtAsc(Study study, String participantId);
}
