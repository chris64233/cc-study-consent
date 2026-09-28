package com.chris64233.cc.studyconsent.domain;

/**
 * 活动在某一时间点被允许或拒绝的原因码，用于授权解释。
 */
public enum DenialReason {

    /** 该时间点方案尚未发布任何版本。 */
    NO_VERSION_PUBLISHED("该时间点研究方案尚未发布任何版本"),

    /** 参与者从未签署过同意。 */
    NEVER_CONSENTED("参与者尚未签署知情同意"),

    /** 同意已被参与者撤回。 */
    CONSENT_WITHDRAWN("同意已被撤回"),

    /** 签署版本之后发布过实质变更版本，旧同意不能授权新活动。 */
    SUBSTANTIVE_VERSION_PUBLISHED("签署版本之后存在实质变更版本，须重新签署当前版本"),

    /** 同意中未选择该活动类型。 */
    ACTIVITY_NOT_SELECTED("同意书未选择该活动类型"),

    /** 评估时间点的生效方案版本不允许该活动类型（含被新版本移除的情形）。 */
    ACTIVITY_NOT_ALLOWED_IN_VERSION("该活动类型不在生效方案版本的允许范围内"),

    /** 研究整体或该类活动在评估时间点处于暂停中，恢复前不得登记新活动。 */
    STUDY_SUSPENDED("研究处于暂停中，恢复前不得登记新活动"),

    /** 暂停期间发布了实质变更版本，受影响参与者须对当前版本重新签署后才能恢复授权。 */
    RECONSENT_REQUIRED_AFTER_SUSPENSION("暂停期间发布了实质变更版本，须对当前版本重新签署后才能恢复授权");

    private final String description;

    DenialReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
