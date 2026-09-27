package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProtocolVersionRepository extends JpaRepository<ProtocolVersion, Long> {

    List<ProtocolVersion> findByStudyOrderByVersionNumberAsc(Study study);

    Optional<ProtocolVersion> findTopByStudyOrderByVersionNumberDesc(Study study);
}
