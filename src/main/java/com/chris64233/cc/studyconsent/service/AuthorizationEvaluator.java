package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.domain.SuspensionDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScope;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.SuspensionDecisionRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权评估器：判定参与者在某一时间点对某活动是否存在有效授权，并给出解释。
 *
 * <p>授权规则（设评估时间为 T，活动类型为 A）：</p>
 * <ol>
 *   <li>T 时刻必须已有发布的方案版本，否则拒绝；生效版本为 T 时刻已发布的最新版本；</li>
 *   <li>A 必须仍在生效版本的允许范围内，否则拒绝（活动已被移除）；</li>
 *   <li>T 时刻若存在覆盖 A 的、尚未被恢复的暂停决定（研究级或活动级），拒绝
 *       （{@link DenialReason#STUDY_SUSPENDED}）。暂停不追溯：仅按决定生效时间判断；</li>
 *   <li>取 T 时刻（含）最近的一个同意事件：
 *     <ul>
 *       <li>无事件 → 未签署；为撤回事件 → 已撤回；</li>
 *       <li>为签署版本 V 的同意：A 必须在选择集合内；</li>
 *       <li>V 之后、T 之前若发布过实质变更版本 → 旧同意失效，须重新签署；</li>
 *       <li>若一次覆盖 A 的暂停（生效晚于该签署）在其暂停期间发布过实质变更版本，
 *           则即使暂停已被恢复，参与者也必须对当前版本重新签署后才能恢复授权
 *           （{@link DenialReason#RECONSENT_REQUIRED}）；暂停期间仅有非实质变更时，
 *           仍按"双方共有活动"规则判断；</li>
 *       <li>否则授权成立。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Component
public class AuthorizationEvaluator {

    private final StudyVersionRepository versionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final SuspensionDecisionRepository suspensionDecisionRepository;

    public AuthorizationEvaluator(StudyVersionRepository versionRepository,
                                  ConsentEventRepository consentEventRepository,
                                  SuspensionDecisionRepository suspensionDecisionRepository) {
        this.versionRepository = versionRepository;
        this.consentEventRepository = consentEventRepository;
        this.suspensionDecisionRepository = suspensionDecisionRepository;
    }

    /** 评估结果。 */
    public sealed interface Result permits Result.Allowed, Result.Refused {

        Integer effectiveVersionNo();

        /** 授权成立。 */
        record Allowed(ConsentEvent consentEvent,
                       StudyVersion effectiveVersion,
                       String message) implements Result {
            @Override
            public Integer effectiveVersionNo() {
                return effectiveVersion.getVersionNo();
            }
        }

        /** 授权不成立。 */
        record Refused(DenialReason reason,
                       String message,
                       StudyVersion effectiveVersion,
                       ConsentEvent lastEvent) implements Result {
            @Override
            public Integer effectiveVersionNo() {
                return effectiveVersion == null ? null : effectiveVersion.getVersionNo();
            }
        }
    }

    /**
     * 评估指定时间点的授权状态。只读操作。
     */
    @Transactional(readOnly = true)
    public Result evaluate(Long studyId, Long participantId, String activityType, Instant at) {
        List<StudyVersion> publishedAt =
                versionRepository.findByStudyIdAndPublishedAtLessThanEqualOrderByVersionNoAsc(studyId, at);
        if (publishedAt.isEmpty()) {
            return new Result.Refused(DenialReason.NO_VERSION_PUBLISHED,
                    DenialReason.NO_VERSION_PUBLISHED.getDescription(), null, null);
        }
        StudyVersion effectiveVersion = publishedAt.get(publishedAt.size() - 1);

        if (!effectiveVersion.getAllowedActivityTypes().contains(activityType)) {
            return new Result.Refused(DenialReason.ACTIVITY_NOT_ALLOWED_IN_VERSION,
                    "活动类型 '" + activityType + "' 不在生效版本 v" + effectiveVersion.getVersionNo()
                            + " 的允许范围内", effectiveVersion, null);
        }

        // T 时点（含）之前已生效的暂停/恢复决定，按生效时间重放
        List<SuspensionDecision> decisions =
                suspensionDecisionRepository.findByStudyIdAndEffectiveAtLessThanEqualOrderByEffectiveAtAscIdAsc(
                        studyId, at);
        // 重放后仍未恢复的暂停：外部事件号 → 暂停决定
        Map<String, SuspensionDecision> active = replayActive(decisions);

        SuspensionDecision blocking = findCovering(active, activityType);
        if (blocking != null) {
            String scopeText = blocking.getScope() == SuspensionScope.STUDY_WIDE
                    ? "整个研究" : "活动类型 " + blocking.getActivityTypes();
            return new Result.Refused(DenialReason.STUDY_SUSPENDED,
                    "研究因外部安全事件 " + blocking.getExternalIncidentId()
                            + " 处于暂停中（暂停事件号 " + blocking.getExternalEventId()
                            + "，范围：" + scopeText + "，自 " + blocking.getEffectiveAt()
                            + " 起生效），活动 '" + activityType + "' 不能登记",
                    effectiveVersion, null);
        }

        List<ConsentEvent> events =
                consentEventRepository.findByStudyIdAndParticipantIdOrderByEventTimeAscIdAsc(
                        studyId, participantId);
        ConsentEvent last = null;
        for (ConsentEvent event : events) {
            if (event.getEventTime().isAfter(at)) {
                break;
            }
            last = event;
        }

        if (last == null) {
            return new Result.Refused(DenialReason.NEVER_CONSENTED,
                    DenialReason.NEVER_CONSENTED.getDescription(), effectiveVersion, null);
        }
        if (last.getEventType() == ConsentEventType.WITHDRAW) {
            return new Result.Refused(DenialReason.CONSENT_WITHDRAWN,
                    "同意已于 " + last.getEventTime() + " 被全部撤回（事件号 "
                            + last.getExternalEventId() + "）", effectiveVersion, last);
        }

        // 最近事件为 GRANT
        if (!last.getSelectedActivityTypes().contains(activityType)) {
            return new Result.Refused(DenialReason.ACTIVITY_NOT_SELECTED,
                    "v" + last.getVersionNo() + " 的同意未选择活动类型 '" + activityType + "'",
                    effectiveVersion, last);
        }

        StudyVersion signedVersion = findVersion(publishedAt, last.getVersionNo());
        if (signedVersion == null) {
            // 签署时间早于该版本发布时间（理论上被服务层阻止），稳健起见按失效处理
            return new Result.Refused(DenialReason.SUBSTANTIVE_VERSION_PUBLISHED,
                    "签署版本 v" + last.getVersionNo() + " 在该时间点尚未发布",
                    effectiveVersion, last);
        }

        StudyVersion firstSubstantive = null;
        for (StudyVersion v : publishedAt) {
            if (v.getVersionNo() > signedVersion.getVersionNo()
                    && v.getChangeType() == ChangeType.SUBSTANTIVE) {
                firstSubstantive = v;
                break;
            }
        }
        if (firstSubstantive != null) {
            // 该实质版本若发布在"覆盖 A 的暂停窗口"内，则属于暂停期实质变更：
            // 窗口在 T 仍未关闭时上面已按 STUDY_SUSPENDED 拒绝；走到这里说明暂停已恢复，
            // 参与者必须对当前版本重新签署后才能"恢复"授权（RECONSENT_REQUIRED）。
            SuspensionDecision suspensionAtPublication =
                    coveringSuspensionAt(decisions, activityType, firstSubstantive.getPublishedAt());
            if (suspensionAtPublication != null) {
                return new Result.Refused(DenialReason.RECONSENT_REQUIRED,
                        "暂停事件 " + suspensionAtPublication.getExternalEventId()
                                + "（外部安全事件 " + suspensionAtPublication.getExternalIncidentId()
                                + "）期间发布了实质变更版本 v" + firstSubstantive.getVersionNo()
                                + "，恢复后须对当前版本重新签署，当前同意仍为 v"
                                + signedVersion.getVersionNo(),
                        effectiveVersion, last);
            }
            return new Result.Refused(DenialReason.SUBSTANTIVE_VERSION_PUBLISHED,
                    "签署版本 v" + signedVersion.getVersionNo() + " 之后已发布实质变更版本 v"
                            + firstSubstantive.getVersionNo()
                            + "，旧同意不能授权新活动，须重新签署当前版本",
                    effectiveVersion, last);
        }

        return new Result.Allowed(last, effectiveVersion,
                "v" + signedVersion.getVersionNo() + " 的同意在非实质变更链下继续授权共有活动 '"
                        + activityType + "'，当前生效版本 v" + effectiveVersion.getVersionNo());
    }

    /** 按生效时间顺序重放暂停/恢复决定，返回重放后仍生效（未恢复）的暂停。 */
    private Map<String, SuspensionDecision> replayActive(List<SuspensionDecision> decisions) {
        Map<String, SuspensionDecision> active = new LinkedHashMap<>();
        for (SuspensionDecision d : decisions) {
            switch (d.getDecisionType()) {
                case SUSPEND -> active.put(d.getExternalEventId(), d);
                case RESUME -> active.remove(d.getSuspendEventId());
            }
        }
        return active;
    }

    /** 重放到指定时间点，返回当时覆盖该活动的生效暂停（研究级优先），无则 null。 */
    private SuspensionDecision coveringSuspensionAt(List<SuspensionDecision> decisions,
                                                    String activityType,
                                                    Instant at) {
        List<SuspensionDecision> upTo = decisions.stream()
                .takeWhile(d -> !d.getEffectiveAt().isAfter(at))
                .toList();
        return findCovering(replayActive(upTo), activityType);
    }

    /** 找到生效暂停中覆盖指定活动类型的一个（研究级优先，其次活动级）。 */
    private SuspensionDecision findCovering(Map<String, SuspensionDecision> active, String activityType) {
        SuspensionDecision activityScoped = null;
        for (SuspensionDecision s : active.values()) {
            if (s.getScope() == SuspensionScope.STUDY_WIDE) {
                return s;
            }
            if (s.getActivityTypes().contains(activityType)) {
                activityScoped = s;
            }
        }
        return activityScoped;
    }

    /**
     * 判断参与者最近一次签署是否在"暂停期间发生实质变更"后未重新签署。
     *
     * <p>遍历已结束（已恢复）的暂停段：暂停覆盖 A、生效晚于本次签署，且暂停区间
     * [暂停生效, 恢复生效] 内发布过版本号高于签署版本的实质变更版本，即须重新签署。
     * 参与者若在该实质版本之后重新签署（last 版本号更高），自然不命中。</p>
     */
    private StudyVersion findVersion(List<StudyVersion> versions, int versionNo) {
        for (StudyVersion v : versions) {
            if (v.getVersionNo() == versionNo) {
                return v;
            }
        }
        return null;
    }
}
