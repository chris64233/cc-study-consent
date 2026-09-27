package com.chris64233.cc.studyconsent.domain;

/**
 * 版本相对上一版本的变更类型。
 */
public enum ChangeType {

    /** 首个版本，没有上一版本可比较。 */
    INITIAL("首次发布"),

    /** 实质变更：旧同意立即失效，参与者必须重新签署当前版本。 */
    SUBSTANTIVE("实质变更"),

    /** 非实质变更：旧同意继续授权两个版本共有的活动。 */
    NON_SUBSTANTIVE("非实质变更");

    private final String description;

    ChangeType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
