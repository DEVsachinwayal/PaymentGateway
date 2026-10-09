package com.apg.common.logging;

import com.apg.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

/** Test helper: a minimal Boot app that logs a few secrets; started in its own JVM by LoggingTest. */
@Configuration
public class JsonLogMain {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JsonLogMain.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(java.util.Map.of("spring.application.name", "log-test"));
        app.run(args).close();
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void log() {
        Logger log = LoggerFactory.getLogger("apg.test");
        MDC.put(CorrelationIdFilter.MDC_KEY, "req_json_test_1");
        log.info("charged card 4111111111111111 with key sk_test_ab12cd34ef56.0123456789abcdef0123456789abcdef");
        log.warn("failure", new IllegalStateException("password=hunter2hunter2 leaked"));
        MDC.clear();
    }
}
