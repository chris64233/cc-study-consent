package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 同意事件（不可修改、不可删除的审计记录）。
 *
 * <p>每个事件携带调用方提供的唯一外部事件号 {@code externalEventId}：</p>
 * <ul>
 *   <li>相同事件号 + 相同内容（类型/版本/活动集合）→ 幂等，返回既有事件；</li>
 *   <li>相同事件号 + 不同内容 → 抛出冲突异常。</li>
 * </ul>
 *
 * <p>{@link ConsentEventType#GRANT} 携带签署版本与选择的活动类型；
 * {@link ConsentEventType#WITHDRAW} 表示撤回该参与者在该研究上的全部同意，
 * {@code versionNo} 为空、活动集合为空。</p>
 */
@Entity
@Table(
        name = "consent_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_consent_external_event",
                columnNames = {"external_event_id"}))
public class ConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的唯一外部事件号，全局唯一。 */
    @Column(name = "external_event_id", nullable = false, length = 128)
    private String externalEventId;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private ConsentEventType eventType;

    /** 签署的方案版本号；撤回事件为 null。 */
    @Column(name = "version_no")
    private Integer versionNo;

    /** 签署时选择的活动类型；撤回事件为空集合。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "consent_event_activity",
            joinColumns = @JoinColumn(name = "consent_event_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_consent_event_activity",
                    columnNames = {"consent_event_id", "activity_type"}))
    @Column(name = "activity_type", nullable = false, length = 64)
    private Set<String> selectedActivityTypes = new HashSet<>();

    /** 事件记录的业务时间（签署时间/撤回指定时间）。 */
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    /** 入库时间，由 Clock 生成，仅作审计。 */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ConsentEvent() {
    }

    private ConsentEvent(String externalEventId,
                         Long studyId,
                         Long participantId,
                         ConsentEventType eventType,
                         Integer versionNo,
                         Set<String> selectedActivityTypes,
                         Instant eventTime,
                         Instant recordedAt) {
        this.externalEventId = externalEventId;
        this.studyId = studyId;
        this.participantId = participantId;
        this.eventType = eventType;
        this.versionNo = versionNo;
        this.selectedActivityTypes = selectedActivityTypes == null
                ? new HashSet<>() : new HashSet<>(selectedActivityTypes);
        this.eventTime = eventTime;
        this.recordedAt = recordedAt;
    }

    public static ConsentEvent grant(String externalEventId,
                                     Long studyId,
                                     Long participantId,
                                     int versionNo,
                                     Set<String> selectedActivityTypes,
                                     Instant signedAt,
                                     Instant recordedAt) {
        return new ConsentEvent(externalEventId, studyId, participantId,
                ConsentEventType.GRANT, versionNo, selectedActivityTypes,
                signedAt, recordedAt);
    }

    public static ConsentEvent withdraw(String externalEventId,
                                        Long studyId,
                                        Long participantId,
                                        Instant withdrawAt,
                                        Instant recordedAt) {
        return new ConsentEvent(externalEventId, studyId, participantId,
                ConsentEventType.WITHDRAW, null, Set.of(),
                withdrawAt, recordedAt);
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

    public ConsentEventType getEventType() {
        return eventType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public Set<String> getSelectedActivityTypes() {
        return Set.copyOf(selectedActivityTypes);
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
