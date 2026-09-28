package com.chris64233.cc.studyconsent.repo;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 是否存在研究内、发生时间不早于指定时间且活动类型在给定集合内的活动。
     * 暂停决定持研究行锁后用它检查：暂停不能追溯否定已经合法发生的活动，
     * 若范围内存在发生时间 ≥ 暂停生效时间的活动，暂停决定被拒绝。
     * 活动类型集合为空时按"整个研究"处理（不按类型过滤）。
     */
    @Query("""
            select count(a) > 0 from ActivityRecord a
            where a.studyId = :studyId
              and a.occurredAt >= :occurredAt
              and ( :allActivities = true or a.activityType in :activityTypes )
            """)
    boolean existsBlockingSuspension(@Param("studyId") Long studyId,
                                     @Param("occurredAt") Instant occurredAt,
                                     @Param("activityTypes") java.util.Collection<String> activityTypes,
                                     @Param("allActivities") boolean allActivities);
}
