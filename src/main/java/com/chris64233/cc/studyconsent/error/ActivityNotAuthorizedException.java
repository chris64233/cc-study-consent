package com.chris64233.cc.studyconsent.error;

import com.chris64233.cc.studyconsent.service.AuthorizationDecision;

/** 活动记录缺少有效授权（HTTP 422），携带拒绝原因以便解释。 */
public class ActivityNotAuthorizedException extends RuntimeException {

    private final AuthorizationDecision decision;

    public ActivityNotAuthorizedException(AuthorizationDecision decision) {
        super(decision.reason());
        this.decision = decision;
    }

    public AuthorizationDecision getDecision() {
        return decision;
    }
}
