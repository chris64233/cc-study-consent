package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 研究活动记录。创建时必须存在有效授权，创建后不可修改、不可删除。
 */
@Entity
@Table(name = "activity_records")
public class ActivityRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "study_id", nullable = false)
    private Study study;

    @Column(name = "participant_id", nullable = false)
    private String participantId;

    @Column(nullable = false)
    private String activityType;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant recordedAt;

    /** 授权该活动的同意事件（审计依据）。 */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_event_id", nullable = false)
    private ConsentEvent authorizedBy;

    protected ActivityRecord() {
    }

    public ActivityRecord(Study study, String participantId, String activityType,
                          Instant occurredAt, Instant recordedAt, ConsentEvent authorizedBy) {
        this.study = study;
        this.participantId = participantId;
        this.activityType = activityType;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.authorizedBy = authorizedBy;
    }

    public Long getId() {
        return id;
    }

    public Study getStudy() {
        return study;
    }

    public String getParticipantId() {
        return participantId;
    }

    public String getActivityType() {
        return activityType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public ConsentEvent getAuthorizedBy() {
        return authorizedBy;
    }
}
