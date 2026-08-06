package com.flexi.validationservice.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

@Configuration
public class RegistryInterceptor {
    @Bean
    public RequestInterceptor requestIdInterceptor() {
        return template -> {
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                template.header("X-traceId", traceId);
            }
            String schemaId = MDC.get("schemaId");
            if (traceId != null) {
                template.header("X-schemaId", schemaId);
            }
        };
    }
}
