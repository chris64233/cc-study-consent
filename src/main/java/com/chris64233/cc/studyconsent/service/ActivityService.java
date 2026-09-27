package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ActivityRecord;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.error.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.repo.ActivityRecordRepository;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ActivityService {

    private final StudyService studyService;
    private final EnrollmentRepository enrollmentRepository;
    private final ConsentEventRepository consentEventRepository;
    private final ActivityRecordRepository activityRecordRepository;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    public ActivityService(StudyService studyService,
                           EnrollmentRepository enrollmentRepository,
                           ConsentEventRepository consentEventRepository,
                           ActivityRecordRepository activityRecordRepository,
                           AuthorizationService authorizationService,
                           Clock clock) {
        this.studyService = studyService;
        this.enrollmentRepository = enrollmentRepository;
        this.consentEventRepository = consentEventRepository;
        this.activityRecordRepository = activityRecordRepository;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    /**
     * 记录研究活动。与撤回共用“参与者 × 研究”悲观锁：若撤回先提交，
     * 本事务在锁释放后重新评估，撤回生效时间之后发生的活动必然被拒绝，
     * 不会出现撤回生效后仍被接受的活动。
     */
    @Transactional
    public ActivityRecord record(String studyCode, String participantId, String activityType,
                                 Instant occurredAt) {
        if (activityType == null || activityType.isBlank()) {
            throw new BusinessValidationException("活动类型不能为空");
        }
        if (occurredAt == null) {
            throw new BusinessValidationException("活动发生时间不能为空");
        }
        Study study = studyService.getStudy(studyCode);

        // 无登记行即无任何同意事件，直接拒绝；有登记行则加锁以与撤回串行化
        enrollmentRepository.findByStudyAndParticipantIdForUpdate(study, participantId)
                .orElseThrow(() -> new ActivityNotAuthorizedException(
                        AuthorizationDecision.denied("参与者未登记，不存在任何同意")));

        AuthorizationDecision decision = authorizationService.evaluate(study, participantId,
                activityType, occurredAt);
        if (!decision.allowed()) {
            throw new ActivityNotAuthorizedException(decision);
        }

        ConsentEvent consent = consentEventRepository.findById(decision.consentEventId())
                .orElseThrow(() -> new IllegalStateException("授权依据的同意事件不存在"));
        ActivityRecord record = new ActivityRecord(study, participantId, activityType,
                occurredAt, clock.instant(), consent);
        return activityRecordRepository.save(record);
    }
}
