package com.flexi.validationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingService {
    private final Cache<String, JsonSchema> cache;
    private final ModelRegistryClient modelRegistryClient;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.builder(
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
            .build();

    private JsonSchema getSchema(String schemaId, int version) {
        String key = buildKey(schemaId, version);
        JsonSchema cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("Cache hit JsonSchema: version='{}'", version);
            return cached;
        }
        log.info("Cache miss JsonSchema, fetching from ModelRegistry: version='{}'", version);
        JsonNode schema = modelRegistryClient.getSchema(schemaId, version);
        JsonSchema jsonSchema = schemaFactory.getSchema(schema);
        cache.put(key, jsonSchema);
        log.info("Cache set JsonSchema: version='{}'", version);
        return jsonSchema;
    }

    private String buildKey(String schemaId, int version) {
        return schemaId + ":" + version;
    }

    public Set<ValidationMessage> validate(String schemaId, int version, JsonNode data) {
        return getSchema(schemaId, version).validate(data);
    }
}
