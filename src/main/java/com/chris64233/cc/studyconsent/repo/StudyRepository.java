package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.Study;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StudyRepository extends JpaRepository<Study, Long> {

    Optional<Study> findByCode(String code);

    /** 发布新版本时对研究行加悲观写锁，保证版本号严格递增且当前版本唯一。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Study s where s.code = :code")
    Optional<Study> findByCodeForUpdate(@Param("code") String code);
}
