package com.chris64233.cc.studyconsent.domain;

/**
 * 新版本相对上一版本的变更性质。
 */
public enum ChangeType {
    /** 实质变更：发布后旧版本同意立即失效，必须重新签署。 */
    SUBSTANTIVE,
    /** 非实质变更：旧版本同意可继续授权两版本共有的活动类型。 */
    NON_SUBSTANTIVE
}
