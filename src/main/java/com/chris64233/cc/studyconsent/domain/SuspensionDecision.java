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
 * 研究暂停/恢复决定（不可修改、不可删除的审计记录，构成研究级时间线）。
 *
 * <p>每个决定携带调用方提供的唯一外部事件号 {@code externalEventId}：</p>
 * <ul>
 *   <li>相同事件号 + 相同内容 → 幂等，返回既有决定；</li>
 *   <li>相同事件号 + 不同内容 → 冲突（409）。</li>
 * </ul>
 *
 * <p>{@link DecisionType#SUSPEND} 记录外部安全事件号 {@code externalIncidentId}、
 * 原因 {@code reason}、生效时间 {@code effectiveAt} 与作用范围
 * （{@link SuspensionScope#STUDY_WIDE} 整个研究，或
 * {@link SuspensionScope#ACTIVITY_TYPES} 指定活动类型集合）。</p>
 *
 * <p>{@link DecisionType#RESUME} 通过 {@code suspendEventId} 引用当前仍生效的
 * 暂停决定，并以 {@code declaredVersionNo} 声明恢复所依据的方案版本；
 * 其生效时间即恢复生效时间。恢复不改变已发生的事实：此前被暂停拦截而未落库的
 * 活动不会补登，已合法发生的活动也不受影响。</p>
 */
@Entity
@Table(
        name = "suspension_decision",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_suspension_external_event",
                columnNames = {"external_event_id"}))
public class SuspensionDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的唯一外部事件号，全局唯一。 */
    @Column(name = "external_event_id", nullable = false, length = 128)
    private String externalEventId;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 16)
    private DecisionType decisionType;

    /** 暂停对应的外部安全事件号；仅 SUSPEND 有值。 */
    @Column(name = "external_incident_id", length = 128)
    private String externalIncidentId;

    /** 暂停原因；仅 SUSPEND 有值。 */
    @Column(name = "reason", length = 1000)
    private String reason;

    /** 暂停作用范围；仅 SUSPEND 有值。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 32)
    private SuspensionScope scope;

    /** scope=ACTIVITY_TYPES 时被暂停的活动类型集合；其余情况为空集合。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "suspension_activity",
            joinColumns = @JoinColumn(name = "suspension_decision_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_suspension_activity",
                    columnNames = {"suspension_decision_id", "activity_type"}))
    @Column(name = "activity_type", nullable = false, length = 64)
    private Set<String> activityTypes = new HashSet<>();

    /** RESUME 引用的暂停决定外部事件号；仅 RESUME 有值。 */
    @Column(name = "suspend_event_id", length = 128)
    private String suspendEventId;

    /** RESUME 声明恢复所依据的方案版本号；仅 RESUME 有值。 */
    @Column(name = "declared_version_no")
    private Integer declaredVersionNo;

    /** 决定生效时间（暂停生效时间 / 恢复生效时间）。 */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /** 入库时间，由 Clock 生成，仅作审计。 */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected SuspensionDecision() {
    }

    private SuspensionDecision(String externalEventId,
                               Long studyId,
                               DecisionType decisionType,
                               String externalIncidentId,
                               String reason,
                               SuspensionScope scope,
                               Set<String> activityTypes,
                               String suspendEventId,
                               Integer declaredVersionNo,
                               Instant effectiveAt,
                               Instant recordedAt) {
        this.externalEventId = externalEventId;
        this.studyId = studyId;
        this.decisionType = decisionType;
        this.externalIncidentId = externalIncidentId;
        this.reason = reason;
        this.scope = scope;
        this.activityTypes = activityTypes == null ? new HashSet<>() : new HashSet<>(activityTypes);
        this.suspendEventId = suspendEventId;
        this.declaredVersionNo = declaredVersionNo;
        this.effectiveAt = effectiveAt;
        this.recordedAt = recordedAt;
    }

    public static SuspensionDecision suspend(String externalEventId,
                                             Long studyId,
                                             String externalIncidentId,
                                             String reason,
                                             SuspensionScope scope,
                                             Set<String> activityTypes,
                                             Instant effectiveAt,
                                             Instant recordedAt) {
        return new SuspensionDecision(externalEventId, studyId, DecisionType.SUSPEND,
                externalIncidentId, reason, scope, activityTypes,
                null, null, effectiveAt, recordedAt);
    }

    public static SuspensionDecision resume(String externalEventId,
                                            Long studyId,
                                            String suspendEventId,
                                            int declaredVersionNo,
                                            Instant effectiveAt,
                                            Instant recordedAt) {
        return new SuspensionDecision(externalEventId, studyId, DecisionType.RESUME,
                null, null, null, Set.of(),
                suspendEventId, declaredVersionNo, effectiveAt, recordedAt);
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

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public String getExternalIncidentId() {
        return externalIncidentId;
    }

    public String getReason() {
        return reason;
    }

    public SuspensionScope getScope() {
        return scope;
    }

    public Set<String> getActivityTypes() {
        return Set.copyOf(activityTypes);
    }

    public String getSuspendEventId() {
        return suspendEventId;
    }

    public Integer getDeclaredVersionNo() {
        return declaredVersionNo;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
