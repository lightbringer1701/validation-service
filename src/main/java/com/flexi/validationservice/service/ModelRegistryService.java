package com.flexi.validationservice.service;

import com.flexi.common.exception.registry.JsonSchemaNotFoundException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModelRegistryService {
    private final ModelRegistryClient modelRegistryClient;

    @Retryable(
            retryFor = Exception.class,
            noRetryFor = JsonSchemaNotFoundException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, maxDelay = 5000, multiplier = 2),
            recover = "recover"
    )
    public JsonNode getSchema(String schemaId, int version) throws Exception {
        int retryCount = Objects.requireNonNull(RetrySynchronizationManager.getContext()).getRetryCount();
        log.info("Fetching #{} from ModelRegistry: schemaId='{}', version='{}'", retryCount, schemaId, version);
        try {
            return modelRegistryClient.getSchema(schemaId, version);
        } catch (FeignException.NotFound e) {
            throw new JsonSchemaNotFoundException(schemaId, version);
        } catch (Exception e) {
            log.warn("Failed fetch #{}: {}", retryCount, e.getLocalizedMessage());
            throw new Exception(e);
        }
    }

    @Recover
    public JsonNode recover(Exception e, String schemaId, int version) throws Exception {
        if (e instanceof JsonSchemaNotFoundException) {
            throw e;
        }
        log.error("All retries exhausted for ModelRegistry: schemaId='{}', version='{}'", schemaId, version);
        throw new Exception("All retries exhausted: " + e.getMessage());
    }
}
