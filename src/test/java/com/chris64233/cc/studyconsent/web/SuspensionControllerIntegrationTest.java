package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.AbstractIntegrationTest;
import com.chris64233.cc.studyconsent.clock.MutableClock;
import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 暂停/恢复 REST 端到端：暂停拦截活动、同号幂等/冲突、恢复校验、
 * 暂停期实质变更后的重新同意、决定时间线。
 */
@AutoConfigureMockMvc
class SuspensionControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StudyService studyService;
    @Autowired
    private MutableClock clock;

    private static final String BASE = "/api/studies/STUDY-H";
    private static final String PP = BASE + "/participants/P-H";

    @Test
    void suspensionAndResumeFlowOverHttp() throws Exception {
        clock.set(Instant.parse("2026-09-01T00:00:00Z"));
        postJson("/api/studies", """
                {"studyCode":"STUDY-H","name":"HTTP暂停研究"}""");
        postJson("/api/participants", """
                {"participantCode":"P-H","name":"吴十"}""");
        postJson(BASE + "/versions", """
                {"changeType":"INITIAL","allowedActivityTypes":["INTERVIEW","BLOOD"]}""");
        postJson(PP + "/consents", """
                {"externalEventId":"G1","activityTypes":["INTERVIEW","BLOOD"]}""");
        clock.advance(java.time.Duration.ofDays(1));

        // 缺少外部安全事件号 → 400
        mockMvc.perform(post(BASE + "/suspensions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"S1","reason":"x"}"""))
                .andExpect(status().isBadRequest());

        // 暂停整个研究
        mockMvc.perform(post(BASE + "/suspensions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"S1","externalIncidentId":"INC-9",
                                 "reason":"严重不良事件"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionType").value("SUSPEND"))
                .andExpect(jsonPath("$.scope").value("STUDY_WIDE"))
                .andExpect(jsonPath("$.externalIncidentId").value("INC-9"));

        // 暂停后活动 → 403 STUDY_SUSPENDED
        mockMvc.perform(post(PP + "/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("STUDY_SUSPENDED"));

        // 同号同内容重放 → 幂等 201；同号异内容 → 409
        mockMvc.perform(post(BASE + "/suspensions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"S1","externalIncidentId":"INC-9",
                                 "reason":"严重不良事件"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalEventId").value("S1"));
        mockMvc.perform(post(BASE + "/suspensions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"S1","externalIncidentId":"INC-9",
                                 "reason":"别的原因"}"""))
                .andExpect(status().isConflict());

        // 恢复声明了非当前版本 → 400
        studyService.publishVersion("STUDY-H", ChangeType.SUBSTANTIVE,
                Set.of("INTERVIEW", "BLOOD"));
        mockMvc.perform(post(BASE + "/resumptions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"R1","suspendEventId":"S1",
                                 "declaredVersionNo":1}"""))
                .andExpect(status().isBadRequest());

        // 合法恢复（声明当前版本 v2）
        clock.advance(java.time.Duration.ofDays(1));
        mockMvc.perform(post(BASE + "/resumptions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"R1","suspendEventId":"S1",
                                 "declaredVersionNo":2}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionType").value("RESUME"))
                .andExpect(jsonPath("$.suspendEventId").value("S1"));

        // 暂停期发布了实质变更，旧同意 → 403 RECONSENT_REQUIRED
        mockMvc.perform(post(PP + "/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A2"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("RECONSENT_REQUIRED"));

        // 重新签署当前版本后放行
        postJson(PP + "/consents", """
                {"externalEventId":"G2","versionNo":2,"activityTypes":["INTERVIEW"]}""");
        mockMvc.perform(post(PP + "/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A3"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveVersionNo").value(2));

        // 决定时间线：暂停 + 恢复两条，顺序正确
        mockMvc.perform(get(BASE + "/suspensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].decisionType").value("SUSPEND"))
                .andExpect(jsonPath("$[1].decisionType").value("RESUME"));
    }

    private void postJson(String url, String body) {
        try {
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
