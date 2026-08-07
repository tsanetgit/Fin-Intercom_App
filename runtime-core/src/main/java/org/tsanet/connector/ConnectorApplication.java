package org.tsanet.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// The connector's first scheduled work: the inbound reconciliation poller. Without
// this annotation @Scheduled is inert and fails SILENTLY - the bean exists, the app
// starts clean, and nothing ever runs.
@EnableScheduling
public class ConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectorApplication.class, args);
    }
}
