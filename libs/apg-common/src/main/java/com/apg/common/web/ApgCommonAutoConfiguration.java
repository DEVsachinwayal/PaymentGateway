package com.apg.common.web;

import com.apg.common.api.GlobalExceptionHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
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
}
