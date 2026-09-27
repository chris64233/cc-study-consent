package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.StudyVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyVersionRepository extends JpaRepository<StudyVersion, Long> {

    List<StudyVersion> findByStudyIdOrderByVersionNoAsc(Long studyId);

    Optional<StudyVersion> findByStudyIdAndVersionNo(Long studyId, Integer versionNo);

    Optional<StudyVersion> findFirstByStudyIdOrderByVersionNoDesc(Long studyId);

    /** 指定时间点已经发布的版本，按版本号升序。 */
    List<StudyVersion> findByStudyIdAndPublishedAtLessThanEqualOrderByVersionNoAsc(
            Long studyId, java.time.Instant at);
}
