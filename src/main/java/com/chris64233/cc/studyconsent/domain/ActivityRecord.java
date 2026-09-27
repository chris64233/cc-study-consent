package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 研究活动执行记录（不可修改、不可删除）。
 *
 * <p>活动只在其"发生时间点"存在有效授权时才允许创建。
 * 创建时把授权所依据的同意事件与当时方案版本快照到记录上，
 * 便于事后追溯"为什么允许"。</p>
 */
@Entity
@Table(
        name = "activity_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_activity_external_event",
                columnNames = {"external_event_id"}))
public class ActivityRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的唯一外部事件号，用于活动记录幂等。 */
    @Column(name = "external_event_id", nullable = false, length = 128)
    private String externalEventId;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "activity_type", nullable = false, length = 64)
    private String activityType;

    /** 活动实际发生时间，授权按该时间点判定。 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 授权所依据的同意事件 id；被拒绝的活动不落库，故落库记录必有值。 */
    @Column(name = "authorized_by_consent_event_id", nullable = false)
    private Long authorizedByConsentEventId;

    /** 授权时生效的方案版本号。 */
    @Column(name = "effective_version_no", nullable = false)
    private Integer effectiveVersionNo;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ActivityRecord() {
    }

    public ActivityRecord(String externalEventId,
                          Long studyId,
                          Long participantId,
                          String activityType,
                          Instant occurredAt,
                          Long authorizedByConsentEventId,
                          Integer effectiveVersionNo,
                          Instant recordedAt) {
        this.externalEventId = externalEventId;
        this.studyId = studyId;
        this.participantId = participantId;
        this.activityType = activityType;
        this.occurredAt = occurredAt;
        this.authorizedByConsentEventId = authorizedByConsentEventId;
        this.effectiveVersionNo = effectiveVersionNo;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public Long getStudyId() {
        return studyId;
    }

    public Long getParticipantId() {
        return participantId;
    }

    public String getActivityType() {
        return activityType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getAuthorizedByConsentEventId() {
        return authorizedByConsentEventId;
    }

    public Integer getEffectiveVersionNo() {
        return effectiveVersionNo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
