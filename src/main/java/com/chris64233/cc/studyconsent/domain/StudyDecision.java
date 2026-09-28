package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 研究暂停/恢复决定（不可修改、不可删除的时间线条目）。
 *
 * <p>两类决定共用一张表、共用同一个外部事件号唯一约束：</p>
 * <ul>
 *   <li>{@link DecisionType#SUSPEND}：研究级决定（{@code participantId} 为空），
 *       记录外部事件号、暂停范围（整个研究或某类活动）、原因与生效时间；</li>
 *   <li>{@link DecisionType#RESUME}：参与者级恢复确认（{@code participantId} 非空），
 *       引用其恢复的那次暂停决定（{@code resumesDecisionId}），
 *       并声明恢复所依据的方案版本（{@code resumeVersionNo}）。</li>
 * </ul>
 *
 * <p>一条暂停对某参与者持续到该参与者自己的恢复确认生效为止；恢复只关闭
 * 它引用的那次暂停，因此旧恢复请求不可能越过后来新生效的暂停。</p>
 */
@Entity
@Table(
        name = "study_decision",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_decision_external_event",
                columnNames = {"external_event_id"}))
public class StudyDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的唯一外部事件号，在暂停与恢复决定之间全局唯一。 */
    @Column(name = "external_event_id", nullable = false, length = 128)
    private String externalEventId;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    /** 恢复确认归属的参与者；暂停决定为 null（研究级）。 */
    @Column(name = "participant_id")
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 16)
    private DecisionType decisionType;

    /** 暂停范围；恢复决定为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 16)
    private SuspensionScopeType scopeType;

    /** ACTIVITY_TYPE 范围下被暂停的活动类型；其余情况为 null。 */
    @Column(name = "scope_activity_type", length = 64)
    private String scopeActivityType;

    /** 暂停原因（外部安全事件说明）；恢复决定为 null。 */
    @Column(name = "reason", length = 1000)
    private String reason;

    /** 决定的业务生效时间：暂停/恢复均按该时间点界定授权区间。 */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /** 恢复决定所关闭的暂停决定 id；暂停决定为 null。 */
    @Column(name = "resumes_decision_id")
    private Long resumesDecisionId;

    /** 恢复决定声明依据的方案版本号；暂停决定为 null。 */
    @Column(name = "resume_version_no")
    private Integer resumeVersionNo;

    /** 入库时间，由 Clock 生成，仅作审计。 */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected StudyDecision() {
    }

    private StudyDecision(String externalEventId,
                          Long studyId,
                          Long participantId,
                          DecisionType decisionType,
                          SuspensionScopeType scopeType,
                          String scopeActivityType,
                          String reason,
                          Instant effectiveAt,
                          Long resumesDecisionId,
                          Integer resumeVersionNo,
                          Instant recordedAt) {
        this.externalEventId = externalEventId;
        this.studyId = studyId;
        this.participantId = participantId;
        this.decisionType = decisionType;
        this.scopeType = scopeType;
        this.scopeActivityType = scopeActivityType;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.resumesDecisionId = resumesDecisionId;
        this.resumeVersionNo = resumeVersionNo;
        this.recordedAt = recordedAt;
    }

    public static StudyDecision suspend(String externalEventId,
                                        Long studyId,
                                        SuspensionScopeType scopeType,
                                        String scopeActivityType,
                                        String reason,
                                        Instant effectiveAt,
                                        Instant recordedAt) {
        return new StudyDecision(externalEventId, studyId, null,
                DecisionType.SUSPEND, scopeType, scopeActivityType, reason,
                effectiveAt, null, null, recordedAt);
    }

    public static StudyDecision resume(String externalEventId,
                                       Long studyId,
                                       Long participantId,
                                       Long resumesDecisionId,
                                       int resumeVersionNo,
                                       Instant effectiveAt,
                                       Instant recordedAt) {
        return new StudyDecision(externalEventId, studyId, participantId,
                DecisionType.RESUME, null, null, null,
                effectiveAt, resumesDecisionId, resumeVersionNo, recordedAt);
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

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public SuspensionScopeType getScopeType() {
        return scopeType;
    }

    public String getScopeActivityType() {
        return scopeActivityType;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Long getResumesDecisionId() {
        return resumesDecisionId;
    }

    public Integer getResumeVersionNo() {
        return resumeVersionNo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
