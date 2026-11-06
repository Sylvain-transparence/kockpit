package org.kockpit.audit;

import org.junit.jupiter.api.Test;
import org.kockpit.audit.api.AuditReport;
import org.kockpit.audit.api.AuditorService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class, obfuscation()))
            .withPropertyValues(
                    "kockpit.domain=acme",
                    "kockpit.env=dev",
                    "kockpit.app-id=hello",
                    "kockpit.audit.trace.enabled=true");

    // a plain Spring Boot app: no build-info goal, so no BuildProperties bean
    @Test
    void starts_without_build_info() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AuditReport report = audit(context.getBean(AuditorService.class));
            assertThat(report.getAppId()).isEqualTo("hello");
            assertThat(report.getVersion()).isEmpty();
            assertThat(report.getArtifact()).isEmpty();
        });
    }

    @Test
    void uses_build_info_when_present() {
        Properties info = new Properties();
        info.setProperty("artifact", "hello-world");
        info.setProperty("version", "1.0.0");
        runner.withBean(BuildProperties.class, () -> new BuildProperties(info)).run(context -> {
            AuditReport report = audit(context.getBean(AuditorService.class));
            assertThat(report.getVersion()).isEqualTo("1.0.0");
            assertThat(report.getArtifact()).isEqualTo("hello-world");
        });
    }

    // package-private in org.kockpit.audit.obfuscate
    private static Class<?> obfuscation() {
        try {
            return Class.forName("org.kockpit.audit.obfuscate.AuditObfuscateAutoConfiguration");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuditReport audit(AuditorService auditor) {
        auditor.startAudit();
        return auditor.stopAudit();
    }
}
