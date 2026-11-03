package org.kockpit.communication.legacy.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kockpit.communication.Message;
import org.kockpit.communication.legacy.dynaconfig.DynaConfigLegacyMessage;
import org.kockpit.communication.legacy.dynaconfig.InstanceInitPropertiesUpdateRequestDto;
import org.kockpit.communication.legacy.dynaconfig.PropertyUpdateMessageRequestDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HealthLegacyMapperTest {

    private final HealthLegacyMapper mapper = new HealthLegacyMapper();

    private static DynaConfigLegacyMessage legacyMessage() {
        return DynaConfigLegacyMessage.builder()
                .messageId("6f1c0a8e-1d3b-4c7a-9f21-8a4e5b6c7d80")
                .internalType("BROADCAST")
                .serviceId("heartbeat")
                .domain("platform")
                .env("dev")
                .applicationId("samples")
                .message(InstanceInitPropertiesUpdateRequestDto.builder()
                        .updates(List.of(PropertyUpdateMessageRequestDto.builder()
                                .propertyName("instance.status")
                                .newValue("UP")
                                .build()))
                        .build())
                .build();
    }

    @Test
    @DisplayName("Mappe le message legacy vers un Message Kockpit")
    void maps_legacy_to_message() {
        Message message = mapper.toMessage(legacyMessage());

        assertThat(message.getId()).isEqualTo("6f1c0a8e-1d3b-4c7a-9f21-8a4e5b6c7d80");
        assertThat(message.getDomain()).isEqualTo("platform");
        assertThat(message.getEnv()).isEqualTo("dev");
        assertThat(message.getAppId()).isEqualTo("samples");
    }

    /**
     * Ce mapper alimente le chemin heartbeat, dont le TTL compare creationDate a une duree en
     * millisecondes. Tant que la date etait en secondes, toute instance issue du systeme
     * legacy etait vue comme datant de 1970 et evincee du cache des sa reception.
     */
    @Test
    @DisplayName("creationDate est en millisecondes epoch, pas en secondes")
    void sets_creation_date_in_milliseconds() {
        Message message = mapper.toMessage(legacyMessage());

        assertThat(message.getCreationDate())
                .isCloseTo(System.currentTimeMillis(), within(60_000L));
    }
}
