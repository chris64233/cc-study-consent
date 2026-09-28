package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.domain.StudyDecision;
import com.chris64233.cc.studyconsent.domain.SuspensionScopeType;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 授权评估器：判定参与者在某一时间点对某活动是否存在有效授权，并给出解释。
 *
 * <p>授权规则（设评估时间为 T，活动类型为 A）：</p>
 * <ol>
 *   <li>T 时刻必须已有发布的方案版本，否则拒绝；生效版本为 T 时刻已发布的最新版本；</li>
 *   <li>T 时刻研究整体或 A 所属类别处于暂停中 → 拒绝（研究暂停）；</li>
 *   <li>A 必须仍在生效版本的允许范围内，否则拒绝（方案范围不符）；</li>
 *   <li>取 T 时刻（含）最近的一个同意事件：
 *     <ul>
 *       <li>无事件 → 未签署；为撤回事件 → 已撤回；</li>
 *       <li>为签署版本 V 的同意：A 必须在选择集合内；</li>
 *       <li>V 之后、T 之前若发布过实质变更版本 → 旧同意失效，须重新签署；
 *           若该实质版本发布于某次暂停期间，拒绝原因标记为"暂停后尚未重新同意"；</li>
 *       <li>否则授权成立。非实质变更链下，A 同时属于 V 与生效版本
 *           （A 在选择集合内 ⇒ A 属于 V；A 在生效版本内），即为"双方共有活动"。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>暂停只按发生时间点判定：暂停生效前已经合法发生的活动不受影响。</p>
 */
@Component
public class AuthorizationEvaluator {

    private final StudyVersionRepository versionRepository;
    private final ConsentEventRepository consentEventRepository;
    private final SuspensionStatusQuery suspensionStatusQuery;

    public AuthorizationEvaluator(StudyVersionRepository versionRepository,
                                  ConsentEventRepository consentEventRepository,
                                  SuspensionStatusQuery suspensionStatusQuery) {
        this.versionRepository = versionRepository;
        this.consentEventRepository = consentEventRepository;
        this.suspensionStatusQuery = suspensionStatusQuery;
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

        // 研究暂停：暂停期间对应范围内的新活动一律不得登记。
        // 若参与者持有的旧同意之后、暂停期间还发布过实质变更版本且尚未重新签署，
        // 优先解释为"暂停后尚未重新同意"，使恢复路径的说明一步到位。
        Optional<StudyDecision> suspension =
                suspensionStatusQuery.activeSuspensionAt(studyId, participantId, activityType, at);
        if (suspension.isPresent()) {
            StudyVersion blocking = last != null && last.getEventType() == ConsentEventType.GRANT
                    && last.getSelectedActivityTypes().contains(activityType)
                    ? substantiveVersionAfterConsent(last, publishedAt) : null;
            if (blocking != null
                    && suspensionStatusQuery.activeSuspensionAt(
                            studyId, participantId, activityType, blocking.getPublishedAt()).isPresent()) {
                return new Result.Refused(DenialReason.RECONSENT_REQUIRED_AFTER_SUSPENSION,
                        "暂停期间发布了实质变更版本 v" + blocking.getVersionNo()
                                + "（签署版本 v" + last.getVersionNo()
                                + "），须对当前版本重新签署并完成恢复确认后才能恢复授权",
                        effectiveVersion, last);
            }
            StudyDecision s = suspension.get();
            String scope = s.getScopeType() == SuspensionScopeType.ACTIVITY_TYPE
                    ? "活动类型 '" + s.getScopeActivityType() + "'"
                    : "整个研究";
            return new Result.Refused(DenialReason.STUDY_SUSPENDED,
                    scope + " 自 " + s.getEffectiveAt() + " 起暂停（事件号 " + s.getExternalEventId()
                            + "，原因：" + s.getReason() + "），恢复前不得登记新活动",
                    effectiveVersion, last);
        }

        if (!effectiveVersion.getAllowedActivityTypes().contains(activityType)) {
            return new Result.Refused(DenialReason.ACTIVITY_NOT_ALLOWED_IN_VERSION,
                    "活动类型 '" + activityType + "' 不在生效版本 v" + effectiveVersion.getVersionNo()
                            + " 的允许范围内", effectiveVersion, null);
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

        StudyVersion substantiveAfterSign = substantiveVersionAfterConsent(last, publishedAt);
        if (substantiveAfterSign != null) {
            // 实质版本若发布于该参与者某次暂停区间内，属于"暂停后尚未重新同意"，
            // 与常规的实质变更失效区分开，便于向参与者解释
            boolean publishedDuringSuspension = suspensionStatusQuery
                    .activeSuspensionAt(studyId, participantId, activityType,
                            substantiveAfterSign.getPublishedAt())
                    .isPresent();
            if (publishedDuringSuspension) {
                return new Result.Refused(DenialReason.RECONSENT_REQUIRED_AFTER_SUSPENSION,
                        "暂停期间发布了实质变更版本 v" + substantiveAfterSign.getVersionNo()
                                + "（签署版本 v" + signedVersion.getVersionNo()
                                + "），须对当前版本重新签署后才能恢复授权",
                        effectiveVersion, last);
            }
            return new Result.Refused(DenialReason.SUBSTANTIVE_VERSION_PUBLISHED,
                    "签署版本 v" + signedVersion.getVersionNo() + " 之后已发布实质变更版本 v"
                            + substantiveAfterSign.getVersionNo()
                            + "，旧同意不能授权新活动，须重新签署当前版本",
                    effectiveVersion, last);
        }

        return new Result.Allowed(last, effectiveVersion,
                "v" + signedVersion.getVersionNo() + " 的同意在非实质变更链下继续授权共有活动 '"
                        + activityType + "'，当前生效版本 v" + effectiveVersion.getVersionNo());
    }

    /** 最近同意事件所签署版本之后、在评估时点之前发布的第一个实质变更版本（无则 null）。 */
    private StudyVersion substantiveVersionAfterConsent(ConsentEvent last, List<StudyVersion> publishedAt) {
        if (last == null || last.getEventType() != ConsentEventType.GRANT) {
            return null;
        }
        for (StudyVersion v : publishedAt) {
            if (v.getVersionNo() > last.getVersionNo()
                    && v.getChangeType() == ChangeType.SUBSTANTIVE) {
                return v;
            }
        }
        return null;
    }

    private StudyVersion findVersion(List<StudyVersion> versions, int versionNo) {
        for (StudyVersion v : versions) {
            if (v.getVersionNo() == versionNo) {
                return v;
            }
        }
        return null;
    }
}
