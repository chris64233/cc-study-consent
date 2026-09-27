package com.chris64233.cc.studyconsent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 参与者。同意与活动记录均归属于参与者。
 */
@Entity
@Table(name = "participant")
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 参与者业务编码，唯一。 */
    @Column(name = "participant_code", nullable = false, unique = true, length = 64)
    private String participantCode;

    @Column(nullable = false, length = 200)
    private String name;

    /** 乐观锁：同一参与者的签署/撤回/活动记录创建串行化。 */
    @Version
    private Long lockVersion;

    protected Participant() {
    }

    public Participant(String participantCode, String name) {
        this.participantCode = participantCode;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getParticipantCode() {
        return participantCode;
    }

    public String getName() {
        return name;
    }
}
