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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 研究方案版本。发布后不可修改（实体不提供任何修改入口，也无更新接口）。
 * 同一研究的当前版本 = versionNumber 最大的版本。
 */
@Entity
@Table(name = "protocol_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"study_id", "version_number"}))
public class ProtocolVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "study_id", nullable = false)
    private Study study;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** 相对上一版本的变更性质；首个版本没有上一版本，允许为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type")
    private ChangeType changeType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "protocol_version_activity_types",
            joinColumns = @JoinColumn(name = "protocol_version_id"))
    @Column(name = "activity_type", nullable = false)
    private Set<String> allowedActivityTypes = new LinkedHashSet<>();

    @Column(nullable = false)
    private Instant publishedAt;

    protected ProtocolVersion() {
    }

    public ProtocolVersion(Study study, int versionNumber, ChangeType changeType,
                           Set<String> allowedActivityTypes, Instant publishedAt) {
        this.study = study;
        this.versionNumber = versionNumber;
        this.changeType = changeType;
        this.allowedActivityTypes = new LinkedHashSet<>(allowedActivityTypes);
        this.publishedAt = publishedAt;
    }

    public Long getId() {
        return id;
    }

    public Study getStudy() {
        return study;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Set<String> getAllowedActivityTypes() {
        return Set.copyOf(allowedActivityTypes);
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
