package org.tsanet.connector.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the admin tail rings to the root logger. Programmatic rather than a logback
 * config file: the app runs on Spring Boot's default logging setup, and a logback.xml
 * would silently replace that whole setup to add one appender.
 */
@Configuration
class AdminLogRingConfiguration {

    /**
     * Ring capacities satisfy the contract's floor (at least 200) with room: the
     * general ring holds 1000 lines, the audit ring 500 events.
     *
     * <p>The root logger is JVM-global while application contexts are not (the test
     * suite boots several), so any appender this class registered earlier is detached
     * by name first — otherwise rings would stack and every line would be recorded
     * once per context. {@code destroyMethod = "stop"} makes a closed context's ring
     * inert even before the next context detaches it.
     */
    @Bean(destroyMethod = "stop")
    RingBufferLogAppender adminLogRing() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender("adminLogRing");
        RingBufferLogAppender appender = new RingBufferLogAppender(1000, 500);
        appender.setName("adminLogRing");
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        return appender;
    }
}
