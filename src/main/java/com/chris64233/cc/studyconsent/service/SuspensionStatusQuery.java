package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.DecisionType;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
import com.chris64233.cc.studyconsent.repo.StudyDecisionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 暂停状态查询：某参与者在某时间点是否处于覆盖某活动的暂停中。
 *
 * <p>一条暂停对某参与者持续到该参与者<b>自己的</b>恢复确认为止——恢复决定是
 * 参与者级的，只关闭它引用的那一次暂停。因此即使恢复存在，也只关闭对应暂停，
 * 后来新生效的暂停仍然有效（旧恢复请求不可能越过后来的暂停）。</p>
 */
@Component
public class SuspensionStatusQuery {

    private final StudyDecisionRepository decisionRepository;

    public SuspensionStatusQuery(StudyDecisionRepository decisionRepository) {
        this.decisionRepository = decisionRepository;
    }

    /**
     * 时间点 T 对该参与者、该活动类型生效中的最近一次暂停（没有则空）。
     */
    @Transactional(readOnly = true)
    public Optional<StudyDecision> activeSuspensionAt(Long studyId,
                                                      Long participantId,
                                                      String activityType,
                                                      Instant at) {
        List<StudyDecision> decisions =
                decisionRepository.findByStudyIdOrderByEffectiveAtAscIdAsc(studyId);

        // 该参与者在 T（含）之前已确认恢复的暂停 id 集合
        Set<Long> resumedIds = new HashSet<>();
        for (StudyDecision d : decisions) {
            if (d.getEffectiveAt().isAfter(at)) {
                break;
            }
            if (d.getDecisionType() == DecisionType.RESUME
                    && participantId.equals(d.getParticipantId())) {
                resumedIds.add(d.getResumesDecisionId());
            }
        }

        StudyDecision open = null;
        for (StudyDecision d : decisions) {
            if (d.getEffectiveAt().isAfter(at)) {
                break;
            }
            if (d.getDecisionType() == DecisionType.SUSPEND
                    && covers(d, activityType)
                    && !resumedIds.contains(d.getId())) {
                open = d;
            }
        }
        return Optional.ofNullable(open);
    }

    /**
     * 该暂停决定在指定活动类型上是否生效。{@code activityType} 为 null 时
     * 仅研究整体暂停命中（用于研究级暂停的恢复判定）。
     */
    public static boolean covers(StudyDecision suspension, String activityType) {
        return suspension.getScopeType() == SuspensionScopeType.STUDY
                || (activityType != null && activityType.equals(suspension.getScopeActivityType()));
    }
}
