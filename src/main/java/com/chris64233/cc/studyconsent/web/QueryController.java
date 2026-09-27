package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.service.AuthorizationDecision;
import com.chris64233.cc.studyconsent.service.QueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/studies/{studyCode}/participants/{participantId}")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** 参与者当前（或指定时刻）授权状态。 */
    @GetMapping("/authorization")
    public QueryService.AuthorizationStatus status(@PathVariable String studyCode,
                                                   @PathVariable String participantId,
                                                   @RequestParam(required = false)
                                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                   Instant at) {
        return queryService.authorizationStatus(studyCode, participantId, at);
    }

    /** 解释某活动类型在某时刻为何允许或拒绝。 */
    @GetMapping("/authorization/explain")
    public AuthorizationDecision explain(@PathVariable String studyCode,
                                         @PathVariable String participantId,
                                         @RequestParam String activityType,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                         Instant at) {
        return queryService.explain(studyCode, participantId, activityType, at);
    }

    /** 完整时间线。 */
    @GetMapping("/timeline")
    public List<QueryService.TimelineEntry> timeline(@PathVariable String studyCode,
                                                     @PathVariable String participantId) {
        return queryService.timeline(studyCode, participantId);
    }
}
