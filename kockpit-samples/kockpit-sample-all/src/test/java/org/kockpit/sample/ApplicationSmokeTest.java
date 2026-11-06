package org.kockpit.sample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test migration Spring Boot 4.1 : verifie que le contexte Spring demarre
 * sans erreur d'auto-configuration (profil Maven par defaut).
 */
@SpringBootTest(properties = {
        "STORAGE_ACCOUNT=devstoreaccount1",
        "STORAGE_KEY=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==",
        "kockpit.aws.region=eu-west-1",
        "kockpit.aws.s3.bucket=smoke-test",
        // profil azure-eventhub : le client Event Hubs ne se connecte qu'au premier envoi
        "kockpit.audit.notification.eventhub.event_hub_name=smoke-test",
        "kockpit.audit.notification.eventhub.connection_string=Endpoint=sb://smoke-test.servicebus.windows.net/;SharedAccessKeyName=smoke;SharedAccessKey=c21va2U=",
        // profil http : le client de la gateway ne se connecte qu'au premier envoi
        "kockpit.gateway.url=http://localhost:1",
        "kockpit.gateway.api-key=kpk_smoke"
})
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
    }
}
