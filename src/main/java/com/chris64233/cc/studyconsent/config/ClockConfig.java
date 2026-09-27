package com.chris64233.cc.studyconsent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 可替换时钟：生产使用系统 UTC 时钟，测试可注入可变 Clock 以控制时间。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
