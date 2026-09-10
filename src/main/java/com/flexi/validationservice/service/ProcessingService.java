package com.flexi.validationservice.service;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingService {
    private final Cache<String, Schema> cache;
    private final ModelRegistryClient modelRegistryClient;

    private final SchemaRegistry schemaRegistry =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_7
            );

    private Schema getSchema(String schemaId, int version) {
        String key = buildKey(schemaId, version);
        Schema cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("Cache hit JsonSchema: version='{}'", version);
            return cached;
        }
        log.info("Cache miss JsonSchema, fetching from ModelRegistry: version='{}'", version);
        JsonNode schemaJson = modelRegistryClient.getSchema(schemaId, version);
        Schema schema = schemaRegistry.getSchema(schemaJson);
        cache.put(key, schema);
        log.info("Cache set JsonSchema: version='{}'", version);
        return schema;
    }

    private String buildKey(String schemaId, int version) {
        return schemaId + ":" + version;
    }

    public List<Error> validate(
            String schemaId,
            int version,
            JsonNode data
    ) {
        Schema schema = getSchema(schemaId, version);
        return schema.validate(
                data.toString(),
                InputFormat.JSON
        );
    }
}
