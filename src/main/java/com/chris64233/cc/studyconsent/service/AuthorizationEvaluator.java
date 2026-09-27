package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.DenialReason;
import com.chris64233.cc.studyconsent.domain.StudyVersion;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.StudyVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 授权评估器：判定参与者在某一时间点对某活动是否存在有效授权，并给出解释。
 *
 * <p>授权规则（设评估时间为 T，活动类型为 A）：</p>
 * <ol>
 *   <li>T 时刻必须已有发布的方案版本，否则拒绝；生效版本为 T 时刻已发布的最新版本；</li>
 *   <li>A 必须仍在生效版本的允许范围内，否则拒绝（活动已被移除）；</li>
 *   <li>取 T 时刻（含）最近的一个同意事件：
 *     <ul>
 *       <li>无事件 → 未签署；为撤回事件 → 已撤回；</li>
 *       <li>为签署版本 V 的同意：A 必须在选择集合内；</li>
 *       <li>V 之后、T 之前若发布过实质变更版本 → 旧同意失效，须重新签署；</li>
 *       <li>否则授权成立。非实质变更链下，A 同时属于 V 与生效版本
 *           （A 在选择集合内 ⇒ A 属于 V；A 在生效版本内），即为"双方共有活动"。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Component
public class AuthorizationEvaluator {

    private final StudyVersionRepository versionRepository;
    private final ConsentEventRepository consentEventRepository;

    public AuthorizationEvaluator(StudyVersionRepository versionRepository,
                                  ConsentEventRepository consentEventRepository) {
        this.versionRepository = versionRepository;
        this.consentEventRepository = consentEventRepository;
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

        for (StudyVersion v : publishedAt) {
            if (v.getVersionNo() > signedVersion.getVersionNo()
                    && v.getChangeType() == ChangeType.SUBSTANTIVE) {
                return new Result.Refused(DenialReason.SUBSTANTIVE_VERSION_PUBLISHED,
                        "签署版本 v" + signedVersion.getVersionNo() + " 之后已发布实质变更版本 v"
                                + v.getVersionNo() + "，旧同意不能授权新活动，须重新签署当前版本",
                        effectiveVersion, last);
            }
        }

        return new Result.Allowed(last, effectiveVersion,
                "v" + signedVersion.getVersionNo() + " 的同意在非实质变更链下继续授权共有活动 '"
                        + activityType + "'，当前生效版本 v" + effectiveVersion.getVersionNo());
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
