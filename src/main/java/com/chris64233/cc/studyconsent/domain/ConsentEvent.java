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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 同意事件（签署或撤回）。不可修改、不可删除。
 * externalEventId 全局唯一，用于幂等：相同事件号且内容相同则重放成功，内容不同则冲突。
 */
@Entity
@Table(name = "consent_events")
public class ConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_event_id", nullable = false, unique = true)
    private String externalEventId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "study_id", nullable = false)
    private Study study;

    @Column(name = "participant_id", nullable = false)
    private String participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentEventType type;

    /** 仅 SIGN：签署时研究的当前版本。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_version_id")
    private ProtocolVersion protocolVersion;

    /** 仅 SIGN：参与者选择的活动类型，必须是方案允许集合的子集。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consent_event_activity_types",
            joinColumns = @JoinColumn(name = "consent_event_id"))
    @Column(name = "activity_type")
    private Set<String> selectedActivityTypes = new LinkedHashSet<>();

    /** 仅 SIGN：签署时间。 */
    @Column(name = "signed_at")
    private Instant signedAt;

    /** 仅 WITHDRAW：撤回生效时间（撤回该时间点上有效的全部同意）。 */
    @Column(name = "effective_at")
    private Instant effectiveAt;

    /** 事件落库时间。 */
    @Column(nullable = false)
    private Instant recordedAt;

    protected ConsentEvent() {
    }

    public static ConsentEvent sign(String externalEventId, Study study, String participantId,
                                    ProtocolVersion protocolVersion, Set<String> selectedActivityTypes,
                                    Instant signedAt, Instant recordedAt) {
        ConsentEvent e = new ConsentEvent();
        e.externalEventId = externalEventId;
        e.study = study;
        e.participantId = participantId;
        e.type = ConsentEventType.SIGN;
        e.protocolVersion = protocolVersion;
        e.selectedActivityTypes = new LinkedHashSet<>(selectedActivityTypes);
        e.signedAt = signedAt;
        e.recordedAt = recordedAt;
        return e;
    }

    public static ConsentEvent withdraw(String externalEventId, Study study, String participantId,
                                        Instant effectiveAt, Instant recordedAt) {
        ConsentEvent e = new ConsentEvent();
        e.externalEventId = externalEventId;
        e.study = study;
        e.participantId = participantId;
        e.type = ConsentEventType.WITHDRAW;
        e.effectiveAt = effectiveAt;
        e.recordedAt = recordedAt;
        return e;
    }

    public Long getId() {
        return id;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public Study getStudy() {
        return study;
    }

    public String getParticipantId() {
        return participantId;
    }

    public ConsentEventType getType() {
        return type;
    }

    public ProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public Set<String> getSelectedActivityTypes() {
        return Set.copyOf(selectedActivityTypes);
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
