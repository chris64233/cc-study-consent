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
 * 暂停 / 恢复 REST 端到端：暂停拦截、幂等/冲突、恢复后重新授权、决定时间线。
 */
@AutoConfigureMockMvc
class SuspensionControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StudyService studyService;
    @Autowired
    private MutableClock clock;

    private static final String BASE = "/api/studies/STUDY-H/participants/P-H";

    private void postJson(String url, String body) {
        try {
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void suspendResumeFlowOverHttp() throws Exception {
        clock.set(Instant.parse("2026-09-01T00:00:00Z"));
        postJson("/api/studies", """
                {"studyCode":"STUDY-H","name":"HTTP暂停研究"}""");
        postJson("/api/participants", """
                {"participantCode":"P-H","name":"吴九"}""");
        postJson("/api/studies/STUDY-H/versions", """
                {"changeType":"INITIAL","allowedActivityTypes":["INTERVIEW","BLOOD"]}""");
        postJson(BASE + "/consents", """
                {"externalEventId":"G1","activityTypes":["INTERVIEW","BLOOD"]}""");

        clock.advance(java.time.Duration.ofDays(1));

        // 暂停整个研究
        mockMvc.perform(post("/api/studies/STUDY-H/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"SEC-1","reason":"严重不良事件","scopeType":"STUDY"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionType").value("SUSPEND"))
                .andExpect(jsonPath("$.scopeType").value("STUDY"))
                .andExpect(jsonPath("$.reason").value("严重不良事件"));

        // 暂停后活动 → 403 STUDY_SUSPENDED
        clock.advance(java.time.Duration.ofHours(1));
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("STUDY_SUSPENDED"));

        // 同号异内容 → 409
        mockMvc.perform(post("/api/studies/STUDY-H/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"SEC-1","reason":"另一个原因","scopeType":"STUDY"}"""))
                .andExpect(status().isConflict());

        // 同号同内容重放 → 幂等 201
        mockMvc.perform(post("/api/studies/STUDY-H/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"SEC-1","reason":"严重不良事件","scopeType":"STUDY"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalEventId").value("SEC-1"));

        // 缺少原因 → 400
        mockMvc.perform(post("/api/studies/STUDY-H/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"SEC-2","scopeType":"STUDY"}"""))
                .andExpect(status().isBadRequest());

        // 声明旧版本恢复（当前为 v1，正确）→ 201
        clock.advance(java.time.Duration.ofDays(1));
        mockMvc.perform(post(BASE + "/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"RES-1","suspensionExternalEventId":"SEC-1","versionNo":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionType").value("RESUME"))
                .andExpect(jsonPath("$.resumesExternalEventId").value("SEC-1"))
                .andExpect(jsonPath("$.resumeVersionNo").value(1));

        // 恢复后活动被接受
        mockMvc.perform(post(BASE + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A2"}"""))
                .andExpect(status().isCreated());

        // 决定时间线含暂停与恢复
        mockMvc.perform(get("/api/studies/STUDY-H/decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].decisionType").value("SUSPEND"))
                .andExpect(jsonPath("$[1].decisionType").value("RESUME"));

        // 参与者时间线也包含这两条决定
        mockMvc.perform(get(BASE + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5)); // v1、暂停、G1、恢复、A2 中的决定条目
    }

    @Test
    void resumeRequiresResignAfterSubstantiveVersionOverHttp() throws Exception {
        clock.set(Instant.parse("2026-09-10T00:00:00Z"));
        studyService.createStudy("STUDY-R", "R");
        studyService.createParticipant("P-R", "R");
        studyService.publishVersion("STUDY-R", ChangeType.INITIAL, Set.of("INTERVIEW"));
        var base = "/api/studies/STUDY-R/participants/P-R";
        postJson(base + "/consents", """
                {"externalEventId":"G1","activityTypes":["INTERVIEW"]}""");

        clock.advance(java.time.Duration.ofDays(2));
        postJson("/api/studies/STUDY-R/suspensions", """
                {"externalEventId":"SEC-1","reason":"安全事件"}""");

        clock.advance(java.time.Duration.ofDays(1));
        studyService.publishVersion("STUDY-R", ChangeType.SUBSTANTIVE, Set.of("INTERVIEW", "MRI"));

        // 未重新签署直接恢复 → 409
        mockMvc.perform(post(base + "/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"RES-1","suspensionExternalEventId":"SEC-1","versionNo":2}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重新签署")));

        // 重新签署当前版本后恢复 → 201
        postJson(base + "/consents", """
                {"externalEventId":"G2","activityTypes":["INTERVIEW","MRI"]}""");
        mockMvc.perform(post(base + "/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"RES-2","suspensionExternalEventId":"SEC-1","versionNo":2}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void activityTypeSuspensionScopesOnlyThatTypeOverHttp() throws Exception {
        studyService.createStudy("STUDY-T", "T");
        studyService.createParticipant("P-T", "T");
        studyService.publishVersion("STUDY-T", ChangeType.INITIAL, Set.of("INTERVIEW", "BLOOD"));
        var base = "/api/studies/STUDY-T/participants/P-T";
        postJson(base + "/consents", """
                {"externalEventId":"G1","activityTypes":["INTERVIEW","BLOOD"]}""");

        mockMvc.perform(post("/api/studies/STUDY-T/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalEventId":"SEC-1","scopeType":"ACTIVITY_TYPE","activityType":"BLOOD","reason":"设备故障"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"BLOOD","externalEventId":"A1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denialReason").value("STUDY_SUSPENDED"));
        mockMvc.perform(post(base + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityType":"INTERVIEW","externalEventId":"A2"}"""))
                .andExpect(status().isCreated());
    }
}
