package org.kockpit.features.heartbeat.services.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.communication.Message;
import org.kockpit.communication.Publisher;
import org.kockpit.features.heartbeat.services.HeartBeatDto;
import org.kockpit.features.heartbeat.services.HeartBeatServiceDefinition;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.util.List;


@RequiredArgsConstructor
@Slf4j
public class HeartBeatPublisher {

    private final List<Publisher> publishers;

    private final TaskScheduler taskScheduler;

    private final HeartBeatServiceDefinition serviceDefinition;

    private String domain;
    private String env;
    private String appId;
    private String instanceId;
    private String startupId;

    void start(String domain, String env, String appId, String instanceId, String startupId, Duration triggerPeriod) {
        this.domain = domain;
        this.env = env;
        this.appId = appId;
        this.instanceId = instanceId;
        this.startupId = startupId;
        log.info("Start heartBeat for domain {}, env {}, scheduler {}, startupId {}", domain, env, triggerPeriod, startupId);
        taskScheduler.schedule(() -> {
            log.trace("heartBeat for domain {}, env {} and appId {}", domain, env, appId);
            publishers.forEach(publisher -> publisher.publish(Message.builder()
                    .id(instanceId) // one message per instance: replicas of an app must not overwrite each other's heartbeat
                    .service(serviceDefinition.name())
                    .type(serviceDefinition.name())
                    .domain(domain)
                    .env(env)
                    .appId(appId)
                    .creationDate(System.currentTimeMillis())
                    .body(HeartBeatDto.builder().instanceId(instanceId).appId(appId).startupId(startupId).build())
                    .build()));
        }, new PeriodicTrigger(triggerPeriod));
    }

    @PreDestroy
    void onStop() {
        log.info("HeartBeat stopped for domain {}, env {}, appId {}", domain, env, appId);
        publishers.forEach(publisher -> publisher.publish(Message.builder()
                .id(instanceId)
                .service(serviceDefinition.name())
                .type(serviceDefinition.name())
                .domain(domain)
                .env(env)
                .appId(appId)
                .creationDate(System.currentTimeMillis())
                .body(HeartBeatDto.builder().instanceId(instanceId).appId(appId).startupId(startupId).ended(true).build())
                .build()));
    }
}
