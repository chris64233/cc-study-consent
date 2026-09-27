package com.chris64233.cc.studyconsent.clock;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 默认时钟：使用系统时间。
 */
@Component
public class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
