package com.apg.common.web;

import com.apg.common.api.GlobalExceptionHandler;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.metrics.DbGauges;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApgCommonAutoConfiguration {
    @Bean CorrelationIdFilter correlationIdFilter() { return new CorrelationIdFilter(); }
    @Bean GlobalExceptionHandler globalExceptionHandler() { return new GlobalExceptionHandler(); }

    /** Enabled only for services that sit behind the gateway (set apg.internal.token). */
    @Bean
    @ConditionalOnProperty(name = "apg.internal.token")
    GatewayTrustFilter gatewayTrustFilter(@Value("${apg.internal.token}") String token) {
        return new GatewayTrustFilter(token);
    }

    @Bean
    @ConditionalOnProperty(name = "apg.internal.token")
    WebMvcConfigurer callerContextConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CallerContextResolver());
            }
        };
    }

    /** Available in services that own an idempotency_keys table and have JDBC. */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    IdempotencyService idempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new IdempotencyService(jdbc, mapper);
    }

    /** Lets a service expose backlog-style gauges computed from its own database. */
    @Bean
    @ConditionalOnBean({JdbcTemplate.class, MeterRegistry.class})
    @ConditionalOnMissingBean
    DbGauges dbGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        return new DbGauges(jdbc, registry);
    }
}
