package com.flexi.validationservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.networknt.schema.*;
import com.networknt.schema.Error;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingService {
    private final Cache<@NonNull String, Schema> cache;
    private final ModelRegistryService modelRegistryService;

    private final SchemaRegistry schemaRegistry =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_7
            );

    private Schema getSchema(String schemaId, int version) throws Exception {
        String key = buildKey(schemaId, version);
        Schema cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("Cache hit JsonSchema: version='{}'", version);
            return cached;
        }
        log.info("Cache miss JsonSchema, fetching from ModelRegistry: version='{}'", version);
        JsonNode schemaJson = modelRegistryService.getSchema(schemaId, version);
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
    ) throws Exception {
        Schema schema = getSchema(schemaId, version);
        return schema.validate(
                data.toString(),
                InputFormat.JSON
        );
    }
}
