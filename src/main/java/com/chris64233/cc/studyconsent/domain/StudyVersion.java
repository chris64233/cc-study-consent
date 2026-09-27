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
 * 研究方案版本。
 *
 * <p>版本号在同一研究内严格递增；发布后不可修改（应用层不提供更新入口）。
 * 每个版本声明允许的活动类型，并标记相对上一版本属于实质/非实质变更。</p>
 */
@Entity
@Table(
        name = "study_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_study_version_no",
                columnNames = {"study_id", "version_no"}))
public class StudyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_id", nullable = false)
    private Long studyId;

    /** 从 1 开始严格递增的版本号。 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private ChangeType changeType;

    /** 该版本允许的活动类型集合，快照存储，发布后不可变。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "study_version_activity",
            joinColumns = @JoinColumn(name = "study_version_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_version_activity",
                    columnNames = {"study_version_id", "activity_type"}))
    @Column(name = "activity_type", nullable = false, length = 64)
    private Set<String> allowedActivityTypes = new HashSet<>();

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected StudyVersion() {
    }

    public StudyVersion(Long studyId,
                        int versionNo,
                        ChangeType changeType,
                        Set<String> allowedActivityTypes,
                        Instant publishedAt) {
        this.studyId = studyId;
        this.versionNo = versionNo;
        this.changeType = changeType;
        this.allowedActivityTypes = new HashSet<>(allowedActivityTypes);
        this.publishedAt = publishedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getStudyId() {
        return studyId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Set<String> getAllowedActivityTypes() {
        // 返回副本：已发布版本快照不允许被调用方修改
        return Set.copyOf(allowedActivityTypes);
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
