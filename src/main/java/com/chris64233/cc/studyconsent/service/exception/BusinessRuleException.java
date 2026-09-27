package com.chris64233.cc.studyconsent.service.exception;

/**
 * 业务规则校验失败（如活动选择超出方案范围、版本号不合法等）。映射 HTTP 400。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
