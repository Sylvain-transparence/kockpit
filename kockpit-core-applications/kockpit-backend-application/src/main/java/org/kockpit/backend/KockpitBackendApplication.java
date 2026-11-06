package org.kockpit.backend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.heartbeat.services.HeartBeatDto;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;

import java.util.List;

@SpringBootApplication(
        scanBasePackages = "org.kockpit"
)
@RequiredArgsConstructor
@Slf4j
public class KockpitBackendApplication implements InitializingBean {

    public static void main(String[] args) {
        SpringApplication.run(KockpitBackendApplication.class, args);
    }

    private final ManifestPollers manifestPollers;
    private final List<OnMessageListener> onMessageListeners;

    @Override
    public void afterPropertiesSet() {
        manifestPollers.start();
    }

    @EventListener
    public void onApplicationEvent(HeartBeatDto heartBeatDto) {
        onMessageListeners.stream().parallel().forEach(OnMessageListener::republish);
    }
}
