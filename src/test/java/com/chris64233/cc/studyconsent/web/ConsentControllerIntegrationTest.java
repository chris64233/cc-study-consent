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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口端到端测试：发布、签署幂等/冲突、活动 403 与拒绝原因、撤回、状态与时间线。
 */
@AutoConfigureMockMvc
class ConsentControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StudyService studyService;
    @Autowired
    private MutableClock clock;

    private static final String BASE = "/api/studies/STUDY-W/participants/P-W";

    private void postJson(String url, String body) {
        try {
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        clock.set(Instant.parse("2026-05-01T00:00:00Z"));
        postJson("/api/studies", """
                {"studyCode":"STUDY-W","name":"HTTP研究"}""");
        postJson("/api/participants", """
                {"participantCode":"P-W","name":"周八"}""");

        // 发布 v1
        mockMvc.perform(post("/api/studies/STUDY-W/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeType":"INITIAL","allowedActivityTypes":["INTERVIEW","BLOOD"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 未同意先活动 → 403 且带拒绝原因
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("NEVER_CONSENTED"));

        // 选择超出范围 → 400
        mockMvc.perform(post(BASE + "/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"G1","activityTypes":["INTERVIEW","MRI"]}"""))
                .andExpect(status().isBadRequest());

        // 正常签署
        mockMvc.perform(post(BASE + "/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"G1","activityTypes":["INTERVIEW","BLOOD"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("GRANT"))
                .andExpect(jsonPath("$.versionNo").value(1));

        // 相同事件号+相同内容 → 幂等 201
        mockMvc.perform(post(BASE + "/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"G1","activityTypes":["INTERVIEW","BLOOD"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalEventId").value("G1"))
                .andExpect(jsonPath("$.versionNo").value(1));

        // 相同事件号+不同内容 → 409
        mockMvc.perform(post(BASE + "/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"G1","activityTypes":["INTERVIEW"]}"""))
                .andExpect(status().isConflict());

        // 活动被接受（时间推进到签署之后）
        clock.advance(java.time.Duration.ofDays(1));
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveVersionNo").value(1));

        // 活动外部事件号幂等
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveVersionNo").value(1));

        // 撤回（时间严格晚于已接受活动）
        clock.advance(java.time.Duration.ofDays(1));
        mockMvc.perform(post(BASE + "/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"W1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("WITHDRAW"));

        // 撤回后活动 → 403
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A3"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("CONSENT_WITHDRAWN"));

        // 状态查询
        mockMvc.perform(get(BASE + "/authorization/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestConsentEventType").value("WITHDRAW"))
                .andExpect(jsonPath("$.activityStatuses[0].allowed").value(false));

        // 时间线查询：v1 发布、G1、A2、W1 共 4 条
        mockMvc.perform(get(BASE + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void publishWithoutChangeTypeIsBadRequest() throws Exception {
        studyService.createStudy("STUDY-Z", "Z");
        studyService.createParticipant("P-Z", "Z");
        mockMvc.perform(post("/api/studies/STUDY-Z/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allowedActivityTypes":["X"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void substantiveVersionFlowOverHttp() throws Exception {
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));
        studyService.createStudy("STUDY-S", "S");
        studyService.createParticipant("P-S", "S");
        studyService.publishVersion("STUDY-S", ChangeType.INITIAL, java.util.Set.of("INTERVIEW"));
        var base = "/api/studies/STUDY-S/participants/P-S";

        postJson(base + "/consents",
                """
                        {"externalEventId":"G1","activityTypes":["INTERVIEW"]}""");

        clock.advance(java.time.Duration.ofDays(10));
        studyService.publishVersion("STUDY-S", ChangeType.SUBSTANTIVE,
                java.util.Set.of("INTERVIEW", "MRI"));

        // 实质变更后旧同意失效 → 403
        mockMvc.perform(post(base + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("SUBSTANTIVE_VERSION_PUBLISHED"));

        // explain 给出解释
        mockMvc.perform(get(base + "/authorization/explain")
                        .param("activityType", "INTERVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.denialReason").value("SUBSTANTIVE_VERSION_PUBLISHED"));
    }
}
