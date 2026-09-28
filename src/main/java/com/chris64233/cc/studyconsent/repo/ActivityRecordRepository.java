package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActivityRecordRepository extends JpaRepository<ActivityRecord, Long> {

    Optional<ActivityRecord> findByExternalEventId(String externalEventId);

    List<ActivityRecord> findByStudyIdAndParticipantIdOrderByOccurredAtAscIdAsc(
            Long studyId, Long participantId);

    /**
     * 是否存在发生时间不早于指定时间的活动。撤回事务持锁后用它检查：
     * 若已有活动发生在撤回生效时点或之后，则不能在该时点撤回，
     * 避免出现"撤回生效后仍被接受的活动"。
     */
    boolean existsByStudyIdAndParticipantIdAndOccurredAtGreaterThanEqual(
            Long studyId, Long participantId, Instant occurredAt);

    /** 研究范围内是否存在发生时间不早于指定时间的活动（任意参与者、任意类型）。 */
    boolean existsByStudyIdAndOccurredAtGreaterThanEqual(Long studyId, Instant occurredAt);

    /** 研究范围内某类活动是否存在发生时间不早于指定时间的记录。 */
    boolean existsByStudyIdAndActivityTypeAndOccurredAtGreaterThanEqual(
            Long studyId, String activityType, Instant occurredAt);
}
