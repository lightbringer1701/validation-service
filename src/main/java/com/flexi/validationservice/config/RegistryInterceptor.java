package com.flexi.validationservice.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegistryInterceptor {
    @Bean
    public RequestInterceptor requestIdInterceptor() {
        return template -> {
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                template.header("X-trace-id", traceId);
            }
            String schemaId = MDC.get("schemaId");
            if (schemaId != null) {
                template.header("X-schema-id", schemaId);
            }
            String serviceName = "validation-service";
            if (schemaId != null) {
                template.header("X-service-name", serviceName);
            }
        };
    }
}
