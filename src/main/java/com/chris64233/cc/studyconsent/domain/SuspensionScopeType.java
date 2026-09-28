package com.chris64233.cc.studyconsent.domain;

/**
 * 暂停决定的作用范围。
 */
public enum SuspensionScopeType {

    /** 暂停整个研究：生效后任何活动类型的新活动都不得登记。 */
    STUDY,

    /** 仅暂停某一类活动：只拦截 {@code scopeActivityType} 对应的活动。 */
    ACTIVITY_TYPE
}
