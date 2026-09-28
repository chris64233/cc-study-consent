package com.chris64233.cc.studyconsent.domain;

/**
 * 研究决定类型：暂停或恢复。两类决定共用同一条不可修改的时间线，
 * 外部事件号在二者之间也全局唯一。
 */
public enum DecisionType {

    /** 暂停决定：暂停整个研究或某类活动。 */
    SUSPEND,

    /** 恢复决定：恢复其引用的那次暂停，并声明所依据的方案版本。 */
    RESUME
}
