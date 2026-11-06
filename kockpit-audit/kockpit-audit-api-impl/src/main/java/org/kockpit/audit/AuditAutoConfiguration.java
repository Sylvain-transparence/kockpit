package org.kockpit.audit;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.audit.api.*;
import org.kockpit.audit.obfuscate.AuditObfuscationSettings;
import org.kockpit.sdk.SdkApplicationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(
        value = "kockpit.audit.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties({AuditObfuscationSettings.class, SdkApplicationProperties.class})
@EnableScheduling
@Slf4j
class AuditAutoConfiguration {

  @Bean
  AuditPostProcessor auditPostProcessor(
      List<AuditModuleIntegration> auditModuleIntegrations,
      AuditObfuscationService auditObfuscationServiceImpl) {
    return new AuditPostProcessor(auditModuleIntegrations, auditObfuscationServiceImpl);
  }

  @ConditionalOnProperty(value = "kockpit.audit.trace.enabled", havingValue = "true")
  @ConditionalOnMissingBean(AuditReportNotificationService.class)
  @Bean
  AuditReportNotificationService auditReportNotificationService() {
    return new AuditTraceNotificationService();
  }

  // BuildProperties exists only when the app's build runs spring-boot-maven-plugin's build-info
  // goal: optional, the audits then carry no version / artifact
  @Bean
  AuditorService auditor(
          SdkApplicationProperties sdkApplicationProperties,
          ObjectProvider<BuildProperties> buildProperties,
          NotificationAuditReportManager notificationAuditReportManager,
          @Value("${kockpit.audit.ttl_default_in_days:5}") Integer defaultAuditTtl) {
    return new KockpitAuditor(
        sdkApplicationProperties.getDomain(),
        sdkApplicationProperties.getEnv(),
        sdkApplicationProperties.getAppId(),
        buildProperties.getIfAvailable(),
        notificationAuditReportManager,
        defaultAuditTtl);
  }

  @Bean
  NotificationAuditReportManager notificationAuditReportManager(
      List<AuditReportNotificationService> auditReportNotificationServices,
      AuditReportsQueueHandler auditReportsQueueHandler,
      @Value("${kockpit.audit.notification.async:true}") boolean async,
      @Value("${kockpit.audit.compression.enabled:true}") boolean compressionEnabled,
      CompressionService compressionService) {
    if (auditReportNotificationServices.isEmpty()) {
      throw new IllegalArgumentException(
          "No AuditReportNotificationService has been configured. "
              + "Please add one to the application context.");
    }
    auditReportNotificationServices.forEach(notificationAuditReportManager ->
            log.info("Registering Audit {}", notificationAuditReportManager.getClass().getSimpleName()));
    return new NotificationAuditReportManager(
            auditReportsQueueHandler,
            compressionService,
            async,
            compressionEnabled
    );
  }

  @Bean
  AuditReportsQueueHandler auditReportsQueueHandler(
          List<AuditReportNotificationService> auditReportNotificationServices,
          @Value("${kockpit.audit.notification.buffer.size:1000}") int bufferSize,
          @Value("${kockpit.audit.notification.buffer.threshold:300}") int bufferThreshold,
          @Value("${kockpit.audit.notification.max-batch-size:1048576}") int maxBatchSize // 1MB default
  ) {
    if (bufferThreshold > maxBatchSize) {
      throw new IllegalArgumentException("bufferThreshold ("+bufferThreshold+") should be less than bufferSize ("+bufferSize+")");
    }
    return new AuditReportsQueueHandler(
            auditReportNotificationServices,
            bufferSize,
            bufferThreshold,
            maxBatchSize
    );
  }

  @ConditionalOnMissingBean(CompressionService.class)
  @Bean
  CompressionService gzipCompressionService() {
    return new GZipCompressionService();
  }

  @Bean
  public KockpitAuditorEvent auditorEvent() {
    return new KockpitAuditorEvent();
  }

  @Bean
  public KockpitAuditorKeyValue auditorKeyValue() {
    return new KockpitAuditorKeyValue();
  }
}
