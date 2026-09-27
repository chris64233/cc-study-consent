package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 研究。一个研究下有严格递增的方案版本序列，同一时刻只有一个当前版本。
 */
@Entity
@Table(name = "study")
public class Study {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码，唯一。 */
    @Column(name = "study_code", nullable = false, unique = true, length = 64)
    private String studyCode;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * 当前版本号。仅由发布新版本的事务更新。
     * 首个版本发布前为 null。
     */
    @Column(name = "current_version")
    private Integer currentVersion;

    /** JPA 乐观锁，保证"读取当前版本 → 发布下一版本"的并发安全。 */
    @Version
    private Long lockVersion;

    protected Study() {
    }

    public Study(String studyCode, String name) {
        this.studyCode = studyCode;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getStudyCode() {
        return studyCode;
    }

    public String getName() {
        return name;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }
}
