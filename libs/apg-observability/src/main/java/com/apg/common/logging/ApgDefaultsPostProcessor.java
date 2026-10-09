package com.apg.common.logging;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Library defaults shared by every service (lowest precedence: anything in application.yml or the environment wins):
 * secrets are masked in structured logs, and trace export stays OFF unless APG_TRACING_ENDPOINT (an OTLP/HTTP traces URL,
 * e.g. http://collector:4318/v1/traces) is set, so a missing collector never produces export errors.
 */
public class ApgDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> d = new HashMap<>();
        d.put("logging.structured.json.customizer", "com.apg.common.logging.MaskingJsonCustomizer");
        d.put("management.metrics.tags.application", "${spring.application.name:apg}");       // lets dashboards/alerts split by service
        d.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");   // p95/p99 latency
        String endpoint = env.getProperty("APG_TRACING_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            d.put("management.otlp.tracing.endpoint", endpoint);
            d.put("management.otlp.tracing.export.enabled", "true");
        } else {
            d.put("management.otlp.tracing.export.enabled", "false");
        }
        env.getPropertySources().addLast(new MapPropertySource("apgDefaults", d));
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
