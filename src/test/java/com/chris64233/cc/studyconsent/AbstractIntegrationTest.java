package com.chris64233.cc.studyconsent;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个测试方法前清空业务表（内存库在同一 Spring 上下文内复用）。
 * 不能用 @Transactional 回滚：并发测试的工作线程需要看到已提交数据。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.update("delete from consent_event_activity");
        jdbcTemplate.update("delete from consent_event");
        jdbcTemplate.update("delete from activity_record");
        jdbcTemplate.update("delete from study_version_activity");
        jdbcTemplate.update("delete from study_version");
        jdbcTemplate.update("delete from participant");
        jdbcTemplate.update("delete from study");
        jdbcTemplate.execute("set referential_integrity true");
    }
}
