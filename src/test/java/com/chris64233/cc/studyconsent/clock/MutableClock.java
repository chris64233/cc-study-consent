package com.chris64233.cc.studyconsent.clock;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 测试用可调时钟。{@link Primary} 保证测试上下文中替换 {@link SystemClock}。
 */
@Component
@Primary
public class MutableClock implements Clock {

    private volatile Instant instant = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
        return instant;
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }
}
