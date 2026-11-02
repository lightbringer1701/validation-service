package com.flexi.validationservice.service;

import com.flexi.common.exception.registry.JsonSchemaNotFoundException;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@org.springframework.test.context.ContextConfiguration(
        classes = ModelRegistryServiceRetryIntegrationTest.RetryTestConfiguration.class
)
class ModelRegistryServiceRetryIntegrationTest {

    private static final String SCHEMA_ID = "test-device";
    private static final int VERSION = 2;

    @Autowired
    private ModelRegistryClient modelRegistryClient;

    @Autowired
    private ModelRegistryService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        reset(modelRegistryClient);
    }

    @Test
    void getSchema_shouldRetryAfterTransientFailureAndReturnSchema() throws Exception {
        JsonNode expected = objectMapper.readTree("{\"type\":\"object\"}");
        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(new RuntimeException("temporary failure"))
                .thenReturn(expected);

        JsonNode actual = service.getSchema(SCHEMA_ID, VERSION);

        assertThat(actual).isSameAs(expected);
        verify(modelRegistryClient, times(2)).getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void getSchema_shouldStopAfterFiveAttemptsAndRecover() throws Exception {
        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(new RuntimeException("registry unavailable"));

        assertThatThrownBy(() -> service.getSchema(SCHEMA_ID, VERSION))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("All retries exhausted");

        verify(modelRegistryClient, times(5)).getSchema(SCHEMA_ID, VERSION);
    }

    @Test
    void getSchema_shouldNotRetryWhenSchemaDoesNotExist() throws Exception {
        FeignException.NotFound notFound = mock(FeignException.NotFound.class);
        when(modelRegistryClient.getSchema(SCHEMA_ID, VERSION))
                .thenThrow(notFound);

        assertThatThrownBy(() -> service.getSchema(SCHEMA_ID, VERSION))
                .isInstanceOf(JsonSchemaNotFoundException.class);

        verify(modelRegistryClient, times(1)).getSchema(SCHEMA_ID, VERSION);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry
    @Import(ModelRegistryService.class)
    static class RetryTestConfiguration {
        @Bean
        ModelRegistryClient modelRegistryClient() {
            return mock(ModelRegistryClient.class);
        }
    }
}
