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
import jakarta.persistence.UniqueConstraint;

/**
 * 参与者在某研究中的登记行。除登记含义外，还作为“参与者 × 研究”维度的
 * 悲观锁载体：撤回与活动记录在同一事务中先锁定该行，从而串行化，
 * 保证撤回生效后不会再有新的活动被接受。
 */
@Entity
@Table(name = "enrollments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "participant_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "study_id", nullable = false)
    private Study study;

    @Column(name = "participant_id", nullable = false)
    private String participantId;

    protected Enrollment() {
    }

    public Enrollment(Study study, String participantId) {
        this.study = study;
        this.participantId = participantId;
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
}
