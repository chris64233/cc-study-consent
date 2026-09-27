package com.chris64233.cc.studyconsent.error;

/** 请求内容不合法（HTTP 400）。 */
public class BusinessValidationException extends RuntimeException {
    public BusinessValidationException(String message) {
        super(message);
    }
}
