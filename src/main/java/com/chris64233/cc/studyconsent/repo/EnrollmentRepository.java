package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.Enrollment;
import com.chris64233.cc.studyconsent.domain.Study;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    Optional<Enrollment> findByStudyAndParticipantId(Study study, String participantId);

    /** 悲观写锁：撤回与活动记录据此串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Enrollment e where e.study = :study and e.participantId = :participantId")
    Optional<Enrollment> findByStudyAndParticipantIdForUpdate(@Param("study") Study study,
                                                              @Param("participantId") String participantId);
}
