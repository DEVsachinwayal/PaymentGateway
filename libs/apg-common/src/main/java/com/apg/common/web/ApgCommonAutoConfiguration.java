package com.apg.common.web;

import com.apg.common.api.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApgCommonAutoConfiguration {
    @Bean CorrelationIdFilter correlationIdFilter() { return new CorrelationIdFilter(); }
    @Bean GlobalExceptionHandler globalExceptionHandler() { return new GlobalExceptionHandler(); }
}
