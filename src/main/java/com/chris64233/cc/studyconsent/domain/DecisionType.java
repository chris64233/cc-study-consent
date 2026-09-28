package com.chris64233.cc.studyconsent.domain;

/**
 * 研究暂停/恢复决定类型。
 */
public enum DecisionType {

    /** 暂停决定：生效后范围内的新活动一律不得登记。 */
    SUSPEND,

    /** 恢复决定：解除其引用的暂停决定，恢复活动登记。 */
    RESUME
}
