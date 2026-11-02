package com.flexi.validationservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ModelRegistryService modelRegistryService;
    private Cache<@NonNull String, Schema> cache;
    private ProcessingService service;

    @BeforeEach
    void setup() {
        cache = Caffeine.newBuilder().build();
        service = new ProcessingService(cache, modelRegistryService);
    }

    @AfterEach
    void tearDown() {
        cache.invalidateAll();
    }

    private static final String SCHEMA_ID = "test-device";
    private static final int VERSION = 2;

    JsonNode deviceSchema = objectMapper.readTree("""
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "Device",
          "type": "object",
          "properties": {
            "name": {
              "type": "string"
            },
            "type": {
              "type": "string",
              "enum": [
                "SERVER",
                "WORKSTATION",
                "ROUTER"
              ]
            },
            "ipAddress": {
              "type": "string"
            }
          },
          "required": [
            "name",
            "type",
            "ipAddress"
          ],
          "additionalProperties": false
        }
        """);

    JsonNode validDevice = objectMapper.readTree("""
        {
            "name": "test-device",
            "type": "WORKSTATION",
            "ipAddress": "10.10.10.10"
        }
    """);

    JsonNode invalidDevice = objectMapper.readTree("""
        {
            "name": "test-device",
            "type": "ARM",
            "ipAddress": "10.10.10.10"
        }
    """);

    @Test
    void validate_shouldValid() throws Exception {
        when(modelRegistryService.getSchema(SCHEMA_ID, VERSION))
                .thenReturn(deviceSchema);

        List<Error> result = service.validate(SCHEMA_ID, VERSION, validDevice);

        assertEquals(0, result.size());
        verify(modelRegistryService)
                .getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void validate_shouldInvalid() throws Exception {
        when(modelRegistryService.getSchema(SCHEMA_ID, VERSION))
                .thenReturn(deviceSchema);
        List<Error> result = service.validate(SCHEMA_ID, VERSION, invalidDevice);

        assertEquals(1, result.size());
        verify(modelRegistryService)
                .getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void validate_shouldValidateCached() throws Exception {
        when(modelRegistryService.getSchema(SCHEMA_ID, VERSION))
                .thenReturn(deviceSchema);

        service.validate(SCHEMA_ID, VERSION, invalidDevice);
        assertThat(cache.getIfPresent(SCHEMA_ID+":"+VERSION)).isNotNull();

        service.validate(SCHEMA_ID, VERSION, invalidDevice);
        verify(modelRegistryService, times(1))
                .getSchema(SCHEMA_ID, VERSION);
    }


    @Test
    void validate_shouldCacheSchemasBySchemaIdAndVersion() throws Exception {
        when(modelRegistryService.getSchema(anyString(), anyInt()))
                .thenReturn(deviceSchema);

        service.validate(SCHEMA_ID, VERSION, validDevice);
        service.validate(SCHEMA_ID, VERSION + 1, validDevice);
        service.validate(SCHEMA_ID + "-other", VERSION, validDevice);

        verify(modelRegistryService).getSchema(SCHEMA_ID, VERSION);
        verify(modelRegistryService).getSchema(SCHEMA_ID, VERSION + 1);
        verify(modelRegistryService).getSchema(SCHEMA_ID + "-other", VERSION);
        verifyNoMoreInteractions(modelRegistryService);
    }

    @Test
    void validate_shouldNotCacheSchemaWhenRegistryFetchFails() throws Exception {
        when(modelRegistryService.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(new RuntimeException("Model Registry unavailable"))
                .thenReturn(deviceSchema);

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.validate(SCHEMA_ID, VERSION, validDevice)
        );

        List<Error> result = service.validate(SCHEMA_ID, VERSION, validDevice);

        assertEquals(0, result.size());
        verify(modelRegistryService, times(2)).getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void validate_shouldReturnAllApplicableValidationErrors() throws Exception {
        when(modelRegistryService.getSchema(SCHEMA_ID, VERSION))
                .thenReturn(deviceSchema);

        JsonNode invalidDeviceWithSeveralProblems = objectMapper.readTree("""
                {
                  "type": "ARM",
                  "unexpected": true
                }
                """);

        List<Error> result = service.validate(
                SCHEMA_ID, VERSION, invalidDeviceWithSeveralProblems);

        assertThat(result.size()).isGreaterThan(1);
    }

}
