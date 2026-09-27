package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.ProtocolVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询：参与者授权状态与完整时间线。
 */
@Service
public class QueryService {

    /** 某一活动类型在某个时间点的授权状态。 */
    public record ActivityAuthorization(String activityType, boolean allowed, String reason,
                                        Integer basedOnVersion) {
    }

    /** 参与者授权状态快照。 */
    public record AuthorizationStatus(String studyCode, String participantId, Instant evaluatedAt,
                                      Integer currentVersion, List<ActivityAuthorization> activities) {
    }

    /** 时间线条目。 */
    public record TimelineEntry(Instant timestamp, String kind, String description,
                                Map<String, Object> details) {
    }

    private final StudyService studyService;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    public QueryService(StudyService studyService,
                        ProtocolVersionRepository protocolVersionRepository,
                        ConsentEventRepository consentEventRepository,
                        ActivityRecordRepository activityRecordRepository,
                        AuthorizationService authorizationService,
                        Clock clock) {
        this.studyService = studyService;
        this.protocolVersionRepository = protocolVersionRepository;
        this.consentEventRepository = consentEventRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    /** 当前时刻（或指定时刻）参与者对当前版本各允许活动类型的授权状态及原因。 */
    @Transactional(readOnly = true)
    public AuthorizationStatus authorizationStatus(String studyCode, String participantId, Instant at) {
        Study study = studyService.getStudy(studyCode);
        Instant evaluatedAt = at != null ? at : clock.instant();
        List<ProtocolVersion> versions = protocolVersionRepository.findByStudyOrderByVersionNumberAsc(study);
        Integer currentVersion = versions.isEmpty() ? null
                : versions.get(versions.size() - 1).getVersionNumber();

        Set<String> activityTypes = versions.isEmpty() ? Set.of()
                : versions.get(versions.size() - 1).getAllowedActivityTypes();
        List<ActivityAuthorization> activities = activityTypes.stream()
                .sorted()
                .map(type -> {
                    AuthorizationDecision d = authorizationService.evaluate(study, participantId, type, evaluatedAt);
                    return new ActivityAuthorization(type, d.allowed(), d.reason(), d.basedOnVersion());
                })
                .toList();
        return new AuthorizationStatus(studyCode, participantId, evaluatedAt, currentVersion, activities);
    }

    /** 解释某活动为何允许或拒绝。 */
    @Transactional(readOnly = true)
    public AuthorizationDecision explain(String studyCode, String participantId, String activityType, Instant at) {
        Study study = studyService.getStudy(studyCode);
        return authorizationService.evaluate(study, participantId, activityType,
                at != null ? at : clock.instant());
    }

    /** 完整时间线：方案版本发布、同意签署、撤回、活动记录，按时间排序。 */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(String studyCode, String participantId) {
        Study study = studyService.getStudy(studyCode);
        List<TimelineEntry> entries = new ArrayList<>();

        for (ProtocolVersion v : protocolVersionRepository.findByStudyOrderByVersionNumberAsc(study)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("versionNumber", v.getVersionNumber());
            details.put("changeType", v.getChangeType() == null ? null : v.getChangeType().name());
            details.put("allowedActivityTypes", v.getAllowedActivityTypes());
            entries.add(new TimelineEntry(v.getPublishedAt(), "VERSION_PUBLISHED",
                    "发布方案版本 v" + v.getVersionNumber(), details));
        }

        for (ConsentEvent e : consentEventRepository
                .findByStudyAndParticipantIdOrderByRecordedAtAsc(study, participantId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("externalEventId", e.getExternalEventId());
            details.put("recordedAt", e.getRecordedAt());
            if (e.getType() == ConsentEventType.SIGN) {
                details.put("versionNumber", e.getProtocolVersion().getVersionNumber());
                details.put("selectedActivityTypes", e.getSelectedActivityTypes());
                entries.add(new TimelineEntry(e.getSignedAt(), "CONSENT_SIGNED",
                        "签署 v" + e.getProtocolVersion().getVersionNumber() + " 同意", details));
            } else {
                details.put("effectiveAt", e.getEffectiveAt());
                entries.add(new TimelineEntry(e.getEffectiveAt(), "CONSENT_WITHDRAWN",
                        "撤回全部同意", details));
            }
        }

        for (ActivityRecord a : activityRecordRepository
                .findByStudyAndParticipantIdOrderByOccurredAtAsc(study, participantId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activityType", a.getActivityType());
            details.put("recordedAt", a.getRecordedAt());
            details.put("authorizedByEventId", a.getAuthorizedBy().getExternalEventId());
            entries.add(new TimelineEntry(a.getOccurredAt(), "ACTIVITY_RECORDED",
                    "记录活动 " + a.getActivityType(), details));
        }

        entries.sort(Comparator.comparing(TimelineEntry::timestamp));
        return entries;
    }
}
