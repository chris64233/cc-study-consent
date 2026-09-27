package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.Study;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StudyRepository extends JpaRepository<Study, Long> {

    Optional<Study> findByStudyCode(String studyCode);

    /**
     * 行级写锁：串行化同一研究的版本发布，保证版本号严格递增、
     * "同一研究同时只有一个当前版本"。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Study s where s.studyCode = :studyCode")
    Optional<Study> findForUpdateByStudyCode(@Param("studyCode") String studyCode);
}
