package com.chris64233.cc.studyconsent.service;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ConsentEvent;
import com.chris64233.cc.studyconsent.domain.ConsentEventType;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.domain.Study;
import com.chris64233.cc.studyconsent.repo.ConsentEventRepository;
import com.chris64233.cc.studyconsent.repo.ProtocolVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 授权判定：某参与者在某时间点对某活动类型是否存在有效授权。
 *
 * 规则：
 * 1. 时间点 at 之前最近一次“决定性事件”是撤回（生效时间 &gt;= 最近一次签署的签署时间）→ 拒绝；
 * 2. 没有签署 → 拒绝；
 * 3. 活动类型不在签署选择范围内 → 拒绝；
 * 4. 签署版本之后、at 之前发布的版本中：出现实质变更 → 拒绝（必须重新签署）；
 *    非实质版本不再允许该活动类型 → 拒绝（只能授权各版本共有的活动）；
 * 5. 其余情况 → 允许，依据为该签署事件。
 */
@Service
public class AuthorizationService {

    private final ConsentEventRepository consentEventRepository;
    private final ProtocolVersionRepository protocolVersionRepository;

    public AuthorizationService(ConsentEventRepository consentEventRepository,
                                ProtocolVersionRepository protocolVersionRepository) {
        this.consentEventRepository = consentEventRepository;
        this.protocolVersionRepository = protocolVersionRepository;
    }

    @Transactional(readOnly = true)
    public AuthorizationDecision evaluate(Study study, String participantId, String activityType, Instant at) {
        List<ConsentEvent> events = consentEventRepository
                .findByStudyAndParticipantIdOrderByRecordedAtAsc(study, participantId);

        ConsentEvent latestSign = events.stream()
                .filter(e -> e.getType() == ConsentEventType.SIGN && !e.getSignedAt().isAfter(at))
                .max(Comparator.comparing(ConsentEvent::getSignedAt))
                .orElse(null);

        ConsentEvent latestWithdrawal = events.stream()
                .filter(e -> e.getType() == ConsentEventType.WITHDRAW && !e.getEffectiveAt().isAfter(at))
                .max(Comparator.comparing(ConsentEvent::getEffectiveAt))
                .orElse(null);

        if (latestWithdrawal != null
                && (latestSign == null || !latestWithdrawal.getEffectiveAt().isBefore(latestSign.getSignedAt()))) {
            return AuthorizationDecision.denied(
                    "参与者已于 " + latestWithdrawal.getEffectiveAt() + " 撤回全部同意（事件 "
                            + latestWithdrawal.getExternalEventId() + "）");
        }
        if (latestSign == null) {
            return AuthorizationDecision.denied("参与者在 " + at + " 之前没有有效的同意签署");
        }

        ProtocolVersion signedVersion = latestSign.getProtocolVersion();
        if (!latestSign.getSelectedActivityTypes().contains(activityType)) {
            return AuthorizationDecision.denied(
                    "活动类型 " + activityType + " 不在参与者签署 v" + signedVersion.getVersionNumber()
                            + " 时选择的范围内: " + latestSign.getSelectedActivityTypes());
        }

        List<ProtocolVersion> laterVersions = protocolVersionRepository
                .findByStudyOrderByVersionNumberAsc(study).stream()
                .filter(v -> v.getVersionNumber() > signedVersion.getVersionNumber()
                        && !v.getPublishedAt().isAfter(at))
                .toList();
        for (ProtocolVersion v : laterVersions) {
            if (v.getChangeType() == ChangeType.SUBSTANTIVE) {
                return AuthorizationDecision.denied(
                        "方案 v" + v.getVersionNumber() + " 为实质变更，v" + signedVersion.getVersionNumber()
                                + " 的同意自 " + v.getPublishedAt() + " 起失效，必须重新签署当前版本");
            }
            if (!v.getAllowedActivityTypes().contains(activityType)) {
                return AuthorizationDecision.denied(
                        "活动类型 " + activityType + " 不在签署版本 v" + signedVersion.getVersionNumber()
                                + " 与非实质版本 v" + v.getVersionNumber() + " 的共有范围内");
            }
        }

        return AuthorizationDecision.allowed(latestSign.getId(), signedVersion.getVersionNumber(),
                "依据参与者对 v" + signedVersion.getVersionNumber() + " 的签署（事件 "
                        + latestSign.getExternalEventId() + "）授权");
    }
}
