package com.chris64233.cc.studyconsent.clock;

import java.time.Instant;

/**
 * 可替换的时间来源。业务代码不直接使用 {@link Instant#now()}，
 * 测试中可注入固定时钟或可调时钟，以验证"某一时间点"的授权状态。
 */
public interface Clock {

    Instant now();
}
