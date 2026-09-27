package com.chris64233.cc.studyconsent.service;

/**
 * 授权判定结果：是否允许，以及允许/拒绝的解释。
 *
 * @param consentEventId 允许时作为依据的同意事件 id，拒绝时为 null
 */
public record AuthorizationDecision(boolean allowed, String reason, Long consentEventId,
                                    Integer basedOnVersion) {

    public static AuthorizationDecision allowed(Long consentEventId, int basedOnVersion, String reason) {
        return new AuthorizationDecision(true, reason, consentEventId, basedOnVersion);
    }

    public static AuthorizationDecision denied(String reason) {
        return new AuthorizationDecision(false, reason, null, null);
    }
}
