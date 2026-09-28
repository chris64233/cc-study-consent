package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.clock.Clock;
import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.Participant;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ParticipantRepository;
import com.chris64233.cc.studyconsent.repo.StudyRepository;
import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import com.chris64233.cc.studyconsent.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 研究活动执行记录。
 *
 * <p>活动必须在"发生时间点"存在有效授权才允许落库。统一锁序为
 * <b>先研究行级写锁、再参与者行级写锁</b>，使活动登记与撤回、暂停/恢复、
 * 版本发布严格串行：</p>
 * <ul>
 *   <li>活动先提交：随后的撤回/暂停若生效时间 ≤ 活动发生时间会被拒绝；</li>
 *   <li>撤回/暂停先生效：授权评估能看到该决定，活动被拒绝。</li>
 * </ul>
 */
@Service
public class ActivityService {

    private final ActivityRecordRepository activityRecordRepository;
    private final ParticipantRepository participantRepository;
    private final StudyRepository studyRepository;
    private final StudyService studyService;
    private final AuthorizationEvaluator evaluator;
    private final Clock clock;

    public ActivityService(ActivityRecordRepository activityRecordRepository,
                           ParticipantRepository participantRepository,
                           StudyRepository studyRepository,
                           StudyService studyService,
                           AuthorizationEvaluator evaluator,
                           Clock clock) {
        this.activityRecordRepository = activityRecordRepository;
        this.participantRepository = participantRepository;
        this.studyRepository = studyRepository;
        this.studyService = studyService;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /**
     * 记录一次活动执行。
     *
     * @param occurredAt 活动发生时间（授权按此时间点判定）；为 null 取 Clock
     * @return 已落库的活动记录（被拒绝时抛出 {@link ActivityNotAuthorizedException}，不落库）
     */
    @Transactional
    public ActivityRecord record(String studyCode,
                                 String participantCode,
                                 String activityType,
                                 String externalEventId,
                                 Instant occurredAt) {
        Study study = studyRepository.findForUpdateByStudyCode(studyCode)
                .orElseThrow(() -> new NotFoundException("研究不存在: " + studyCode));
        Participant participant = studyService.getParticipant(participantCode);

        // 统一锁序：研究锁之后锁参与者行
        participantRepository.findForUpdateById(participant.getId()).orElseThrow();

        // 外部事件号幂等：相同号返回既有记录（不重复记录活动）
        ActivityRecord existing = activityRecordRepository.findByExternalEventId(externalEventId).orElse(null);
        if (existing != null) {
            if (!existing.getStudyId().equals(study.getId())
                    || !existing.getParticipantId().equals(participant.getId())
                    || !existing.getActivityType().equals(activityType)
                    || (occurredAt != null && !existing.getOccurredAt().equals(occurredAt))) {
                throw new ConflictException("外部事件号 " + externalEventId
                        + " 已用于不同的活动记录，内容冲突");
            }
            return existing;
        }

        Instant at = occurredAt != null ? occurredAt : clock.now();
        AuthorizationEvaluator.Result result =
                evaluator.evaluate(study.getId(), participant.getId(), activityType, at);
        if (result instanceof AuthorizationEvaluator.Result.Refused refused) {
            throw new ActivityNotAuthorizedException(new ActivityNotAuthorizedException.AuthorizationRefusal(
                    refused.reason(), refused.message(),
                    refused.effectiveVersionNo(),
                    refused.lastEvent() == null ? null : refused.lastEvent().getId()));
        }
        AuthorizationEvaluator.Result.Allowed allowed = (AuthorizationEvaluator.Result.Allowed) result;

        return activityRecordRepository.save(new ActivityRecord(
                externalEventId, study.getId(), participant.getId(), activityType, at,
                allowed.consentEvent().getId(),
                allowed.effectiveVersion().getVersionNo(),
                clock.now()));
    }

    @Transactional(readOnly = true)
    public ActivityRecord getByExternalEventId(String externalEventId) {
        return activityRecordRepository.findByExternalEventId(externalEventId)
                .orElseThrow(() -> new NotFoundException("活动记录不存在: " + externalEventId));
    }

    @Transactional(readOnly = true)
    public List<ActivityRecord> listActivities(Long studyId, Long participantId) {
        return activityRecordRepository
                .findByStudyIdAndParticipantIdOrderByOccurredAtAscIdAsc(studyId, participantId);
    }
}
