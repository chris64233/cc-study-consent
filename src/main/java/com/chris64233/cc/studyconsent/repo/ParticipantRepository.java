package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.Participant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    Optional<Participant> findByParticipantCode(String participantCode);

    /**
     * 行级写锁：把同一参与者的签署、撤回、活动创建串行化，
     * 消除"撤回与活动记录并发"可能产生的时序竞态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Participant p where p.id = :id")
    Optional<Participant> findForUpdateById(@Param("id") Long id);
}
