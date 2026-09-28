package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.StudyDecisionRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 查询参与者授权状态与完整时间线，并能解释某活动为何允许或拒绝。
 */
@Service
public class AuthorizationQueryService {

    private final StudyService studyService;
    private final StudyVersionRepository versionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final StudyDecisionRepository decisionRepository;
    private final AuthorizationEvaluator evaluator;
    private final Clock clock;

    public AuthorizationQueryService(StudyService studyService,
                                     StudyVersionRepository versionRepository,
                                     ConsentEventRepository consentEventRepository,
                                     ActivityRecordRepository activityRecordRepository,
                                     StudyDecisionRepository decisionRepository,
                                     AuthorizationEvaluator evaluator,
                                     Clock clock) {
        this.studyService = studyService;
        this.versionRepository = versionRepository;
        this.consentEventRepository = consentEventRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.decisionRepository = decisionRepository;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /** 时间线条目。 */
    public sealed interface TimelineEntry {

        Instant at();

        /** 方案版本发布。 */
        record VersionPublished(Instant at,
                                int versionNo,
                                String changeType,
                                java.util.Set<String> allowedActivityTypes) implements TimelineEntry {
        }

        /** 同意事件（签署/撤回）。 */
        record Consent(Instant at,
                       String externalEventId,
                       ConsentEventType eventType,
                       Integer versionNo,
                       java.util.Set<String> selectedActivityTypes) implements TimelineEntry {
        }

        /**
         * 研究决定：研究级暂停，或该参与者自己的恢复确认（他人的恢复不展示）。
         */
        record Decision(Instant at,
                        String externalEventId,
                        String decisionType,
                        String scopeType,
                        String scopeActivityType,
                        String reason,
                        Integer resumeVersionNo,
                        String resumesExternalEventId) implements TimelineEntry {
        }

        /** 活动记录（仅被接受的活动会落库）。 */
        record Activity(Instant at,
                        String externalEventId,
                        String activityType,
                        boolean allowed,
                        Integer effectiveVersionNo,
                        Long authorizedByConsentEventId,
                        String explanation) implements TimelineEntry {
        }
    }

    /**
     * 完整时间线：版本发布、同意事件、活动记录按时间合并（同时间按 版本→同意→活动 排序），
     * 每条活动附带"为什么允许"的说明。
     */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(String studyCode, String participantCode) {
        Study study = studyService.getStudy(studyCode);
        Participant participant = studyService.getParticipant(participantCode);

        List<TimelineEntry> entries = new ArrayList<>();

        for (StudyVersion v : versionRepository.findByStudyIdOrderByVersionNoAsc(study.getId())) {
            entries.add(new TimelineEntry.VersionPublished(
                    v.getPublishedAt(), v.getVersionNo(),
                    v.getChangeType().name(), v.getAllowedActivityTypes()));
        }
        for (ConsentEvent e : consentEventRepository
                .findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(study.getId(), participant.getId())) {
            entries.add(new TimelineEntry.Consent(e.getEventTime(), e.getExternalEventId(),
                    e.getEventType(), e.getVersionNo(), e.getSelectedActivityTypes()));
        }
        for (StudyDecision d : decisionRepository
                .findByStudyIdOrderByEffectiveAtAscIdAsc(study.getId())) {
            // 展示所有研究级暂停，以及该参与者自己的恢复确认（其他参与者的恢复与其无关）
            boolean ownResume = d.getDecisionType() == com.chris64233.cc.studyconsent.domain.DecisionType.RESUME
                    && participant.getId().equals(d.getParticipantId());
            if (d.getDecisionType() == com.chris64233.cc.studyconsent.domain.DecisionType.SUSPEND
                    || ownResume) {
                String resumesEventId = null;
                if (d.getResumesDecisionId() != null) {
                    resumesEventId = decisionRepository.findById(d.getResumesDecisionId())
                            .map(StudyDecision::getExternalEventId).orElse(null);
                }
                entries.add(new TimelineEntry.Decision(d.getEffectiveAt(), d.getExternalEventId(),
                        d.getDecisionType().name(),
                        d.getScopeType() == null ? null : d.getScopeType().name(),
                        d.getScopeActivityType(), d.getReason(),
                        d.getResumeVersionNo(), resumesEventId));
            }
        }
        for (ActivityRecord a : activityRecordRepository
                .findByStudyIdAndParticipantIdOrderByOccurredAtAscIdAsc(study.getId(), participant.getId())) {
            entries.add(new TimelineEntry.Activity(a.getOccurredAt(), a.getExternalEventId(),
                    a.getActivityType(), true, a.getEffectiveVersionNo(),
                    a.getAuthorizedByConsentEventId(), explainAccepted(a)));
        }

        // 版本发布(0) → 暂停/恢复决定(1) → 同意(2) → 活动(3)，保证同一时间点因果可读
        entries.sort(Comparator.comparing(TimelineEntry::at)
                .thenComparingInt(e -> switch (e) {
                    case TimelineEntry.VersionPublished ignored -> 0;
                    case TimelineEntry.Decision ignored -> 1;
                    case TimelineEntry.Consent ignored -> 2;
                    case TimelineEntry.Activity ignored -> 3;
                }));
        return entries;
    }

    private String explainAccepted(ActivityRecord a) {
        return "活动发生于 " + a.getOccurredAt() + "，由同意事件 id="
                + a.getAuthorizedByConsentEventId() + " 授权，当时生效版本 v"
                + a.getEffectiveVersionNo();
    }

    /**
     * 评估某活动在指定时间点（默认 Clock 当前时间）为何允许或拒绝。
     */
    @Transactional(readOnly = true)
    public AuthorizationEvaluator.Result explain(String studyCode,
                                                 String participantCode,
                                                 String activityType,
                                                 Instant at) {
        Study study = studyService.getStudy(studyCode);
        Participant participant = studyService.getParticipant(participantCode);
        return evaluator.evaluate(study.getId(), participant.getId(),
                activityType, at != null ? at : clock.now());
    }

    /**
     * 当前（Clock 此刻）授权状态快照：对当前版本允许的每种活动逐一给出判定，
     * 并列出参与者最近一次同意事件的信息。
     */
    @Transactional(readOnly = true)
    public AuthorizationSnapshot status(String studyCode, String participantCode) {
        Study study = studyService.getStudy(studyCode);
        Participant participant = studyService.getParticipant(participantCode);
        Instant now = clock.now();

        Integer currentVersionNo = study.getCurrentVersion();
        StudyVersion current = currentVersionNo == null ? null
                : versionRepository.findByStudyIdAndVersionNo(study.getId(), currentVersionNo).orElse(null);

        List<ConsentEvent> events = consentEventRepository
                .findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(study.getId(), participant.getId());
        ConsentEvent latestEvent = events.isEmpty() ? null : events.get(events.size() - 1);

        List<ActivityStatus> statuses = new ArrayList<>();
        if (current != null) {
            for (String activityType : current.getAllowedActivityTypes().stream().sorted().toList()) {
                AuthorizationEvaluator.Result result =
                        evaluator.evaluate(study.getId(), participant.getId(), activityType, now);
                statuses.add(new ActivityStatus(activityType,
                        result instanceof AuthorizationEvaluator.Result.Allowed,
                        result.effectiveVersionNo(),
                        result instanceof AuthorizationEvaluator.Result.Allowed allowed
                                ? allowed.message()
                                : ((AuthorizationEvaluator.Result.Refused) result).message(),
                        result instanceof AuthorizationEvaluator.Result.Refused refused
                                ? refused.reason().name() : null));
            }
        }
        return new AuthorizationSnapshot(studyCode, participantCode, now,
                currentVersionNo,
                latestEvent == null ? null : latestEvent.getEventType().name(),
                latestEvent == null ? null : latestEvent.getEventTime(),
                latestEvent == null ? null : latestEvent.getVersionNo(),
                statuses);
    }

    /** 当前授权快照。 */
    public record AuthorizationSnapshot(
            String studyCode,
            String participantCode,
            Instant evaluatedAt,
            Integer currentVersionNo,
            String latestConsentEventType,
            Instant latestConsentEventTime,
            Integer latestConsentVersionNo,
            List<ActivityStatus> activityStatuses) {
    }

    /** 单个活动类型的当前授权状态。 */
    public record ActivityStatus(
            String activityType,
            boolean allowed,
            Integer effectiveVersionNo,
            String explanation,
            String denialReason) {
    }
}
