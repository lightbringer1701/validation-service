package com.flexi.validationservice.config;

import java.time.Duration;

import com.networknt.schema.Schema;
import lombok.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
public class SchemaCacheConfig {
    @Bean
    public Cache<@NonNull String, Schema> schemaCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }
}
