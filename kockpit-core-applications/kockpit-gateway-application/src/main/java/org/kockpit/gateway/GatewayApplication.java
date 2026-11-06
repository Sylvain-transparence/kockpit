package org.kockpit.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Ingestion gateway: the only Kockpit endpoint applications outside the cluster talk to.
 * Every call carries an API key ({@code X-Kockpit-Api-Key}); the key decides the domain (its
 * tenant) and the envs, whatever the payload says. See {@link IngestApi}.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
