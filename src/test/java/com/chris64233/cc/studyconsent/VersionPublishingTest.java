package com.chris64233.cc.studyconsent;

import com.chris64233.cc.studyconsent.domain.ChangeType;
import com.chris64233.cc.studyconsent.domain.ProtocolVersion;
import com.chris64233.cc.studyconsent.error.BusinessConflictException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestClockConfig.class)
class VersionPublishingTest {

    @Autowired
    StudyService studyService;

    @Autowired
    MutableClock clock;

    private String code;

    @BeforeEach
    void setUp() {
        clock.setInstant(TestClockConfig.T0);
        code = "S-" + UUID.randomUUID();
        studyService.createStudy(code, "测试研究");
    }

    @Test
    void versionsAreStrictlyIncreasingAndCurrentIsUnique() {
        ProtocolVersion v1 = studyService.publishVersion(code, Set.of("SURVEY"), null);
        ProtocolVersion v2 = studyService.publishVersion(code, Set.of("SURVEY", "BLOOD_DRAW"),
                ChangeType.NON_SUBSTANTIVE);
        ProtocolVersion v3 = studyService.publishVersion(code, Set.of("BLOOD_DRAW"),
                ChangeType.SUBSTANTIVE);

        assertThat(v1.getVersionNumber()).isEqualTo(1);
        assertThat(v2.getVersionNumber()).isEqualTo(2);
        assertThat(v3.getVersionNumber()).isEqualTo(3);

        List<ProtocolVersion> versions = studyService.listVersions(code);
        assertThat(versions).extracting(ProtocolVersion::getVersionNumber)
                .containsExactly(1, 2, 3);
        // 当前版本唯一：最大版本号
        assertThat(versions.get(versions.size() - 1).getVersionNumber()).isEqualTo(3);
    }

    @Test
    void publishedVersionsAreImmutable() {
        ProtocolVersion v1 = studyService.publishVersion(code, Set.of("SURVEY"), null);
        studyService.publishVersion(code, Set.of("SURVEY", "FITNESS"), ChangeType.NON_SUBSTANTIVE);

        // 重新读取 v1：内容未被后续发布影响，且实体不提供任何修改入口
        ProtocolVersion reloaded = studyService.listVersions(code).get(0);
        assertThat(reloaded.getVersionNumber()).isEqualTo(1);
        assertThat(reloaded.getChangeType()).isNull();
        assertThat(reloaded.getAllowedActivityTypes()).containsExactlyInAnyOrder("SURVEY");
        assertThat(reloaded.getPublishedAt()).isEqualTo(v1.getPublishedAt());
    }

    @Test
    void firstVersionMustNotDeclareChangeType() {
        assertThatThrownBy(() -> studyService.publishVersion(code, Set.of("SURVEY"),
                ChangeType.NON_SUBSTANTIVE))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void laterVersionsMustDeclareChangeType() {
        studyService.publishVersion(code, Set.of("SURVEY"), null);
        assertThatThrownBy(() -> studyService.publishVersion(code, Set.of("SURVEY"), null))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void versionMustAllowAtLeastOneActivityType() {
        assertThatThrownBy(() -> studyService.publishVersion(code, Set.of(), null))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void duplicateStudyCodeIsRejected() {
        assertThatThrownBy(() -> studyService.createStudy(code, "重复"))
                .isInstanceOf(BusinessConflictException.class);
    }
}
