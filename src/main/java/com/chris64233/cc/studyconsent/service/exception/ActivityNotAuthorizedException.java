package com.chris64233.cc.studyconsent.service.exception;

import com.chris64233.cc.studyconsent.domain.DenialReason;

/**
 * 活动在指定发生时间点不存在有效授权。映射 HTTP 403，
 * 并携带拒绝原因码，便于调用方向参与者解释。
 */
public class ActivityNotAuthorizedException extends RuntimeException {

    private final transient AuthorizationRefusal refusal;

    public ActivityNotAuthorizedException(AuthorizationRefusal refusal) {
        super(refusal.reason().getDescription());
        this.refusal = refusal;
    }

    public AuthorizationRefusal getRefusal() {
        return refusal;
    }

    /** 拒绝详情。 */
    public record AuthorizationRefusal(
            DenialReason reason,
            String message,
            Integer effectiveVersionNo,
            Long consentEventId) {
    }
}
