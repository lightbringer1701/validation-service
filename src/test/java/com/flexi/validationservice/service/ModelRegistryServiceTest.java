package com.flexi.validationservice.service;

import com.flexi.common.exception.registry.JsonSchemaNotFoundException;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelRegistryServiceTest {

    @BeforeEach
    void setUp() {
        RetryContext retryContext = mock(RetryContext.class);
        when(retryContext.getRetryCount())
                .thenReturn(0, 1, 2, 3, 4);
        RetrySynchronizationManager.register(retryContext);
    }

    @AfterEach
    void tearDown() {
        RetrySynchronizationManager.clear();
    }

    @Mock
    private ModelRegistryClient modelRegistryClient;

    @InjectMocks
    private ModelRegistryService service;

    private static final String SCHEMA_ID = "test-device";
    private static final int VERSION = 2;

    ObjectMapper objectMapper = new ObjectMapper();

    JsonNode expectedDevice = objectMapper.readTree("""
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

    @Test
    void getSchema_shouldReturnSchema() throws Exception {
        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenReturn(expectedDevice);

        JsonNode result = service.getSchema(SCHEMA_ID, VERSION);

        assertThat(result).isSameAs(expectedDevice);
        verify(modelRegistryClient).getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void getSchema_shouldThrowJsonSchemaNotFoundException() throws Exception {
        FeignException.NotFound notFound = mock(FeignException.NotFound.class);
        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(notFound);

        assertThatThrownBy(() -> service.getSchema(SCHEMA_ID, VERSION))
                .isInstanceOf(JsonSchemaNotFoundException.class);
        verify(modelRegistryClient, times(1))
                .getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void getSchema_shouldWrapException() {
        RuntimeException original =
                new RuntimeException("Model Registry unavailable");

        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(original);

        assertThatThrownBy(() ->
                service.getSchema(SCHEMA_ID, VERSION)
        )
                .isInstanceOf(Exception.class)
                .hasCause(original);

        verify(modelRegistryClient)
                .getSchema(SCHEMA_ID, VERSION);
    }
}