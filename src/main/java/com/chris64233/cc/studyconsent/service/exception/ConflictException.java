package com.chris64233.cc.studyconsent.service.exception;

/**
 * 同一外部事件号携带不同内容，或状态与请求冲突（如撤回时间点之后已有活动）。
 * 映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
