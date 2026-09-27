package com.chris64233.cc.studyconsent.error;

/** 幂等冲突等业务冲突（HTTP 409）。 */
public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) {
        super(message);
    }
}
