package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.Study;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRecordRepository extends JpaRepository<ActivityRecord, Long> {

    List<ActivityRecord> findByStudyAndParticipantIdOrderByOccurredAtAsc(Study study, String participantId);
}
