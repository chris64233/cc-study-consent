package com.chris64233.cc.studyconsent.domain;

/**
 * 暂停决定的作用范围。
 */
public enum SuspensionScope {

    /** 暂停整个研究：该研究下任何活动类型的新活动均不得登记。 */
    STUDY_WIDE,

    /** 仅暂停指定活动类型集合内的活动，其余活动照常。 */
    ACTIVITY_TYPES
}
