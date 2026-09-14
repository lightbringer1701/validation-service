package com.flexi.validationservice.config;

import com.flexi.common.exception.registry.JsonSchemaNotFoundException;
import com.flexi.common.payload.OutcomingPayload;
import com.flexi.validationservice.service.ProcessingService;
import com.networknt.schema.*;
import com.networknt.schema.Error;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamFunctionConfigTest {

    @Mock
    private ProcessingService processingService;
    @Mock
    private StreamBridge streamBridge;
    private StreamFunctionConfig stream;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SchemaRegistry schemaRegistry =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_7
            );

    private static final String KEY = "kafka-message-test-key";
    private static final String SCHEMA_ID = "test-device";
    private static final int VERSION = 2;
    private static final String FAILED_BINDING = "failed-out-0";

    private static final JsonNode deviceSchema = objectMapper.readTree("""
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

    private static final JsonNode validDevice = objectMapper.readTree("""
                {
                    "name": "test-device",
                    "type": "WORKSTATION",
                    "ipAddress": "10.10.10.10"
                }
            """);

    private static final JsonNode invalidDevice = objectMapper.readTree("""
                {
                    "name": "test-device",
                    "type": "ARM",
                    "ipAddress": "10.10.10.10"
                }
            """);

    private static final OutcomingPayload expectedPayload = new OutcomingPayload(
            "trace-test",
            SCHEMA_ID,
            VERSION,
            "validate-device-test",
            null,
            validDevice
    );

    private static final OutcomingPayload unexpectedPayload = new OutcomingPayload(
            "trace-test",
            SCHEMA_ID,
            VERSION,
            "validate-device-test",
            null,
            invalidDevice
    );

    private Message<@NonNull OutcomingPayload> buildMessage(OutcomingPayload payload) {
        return MessageBuilder
                .withPayload(payload)
                .setHeader(KafkaHeaders.RECEIVED_KEY, KEY)
                .build();
    }

    private static final Schema schema = schemaRegistry.getSchema(deviceSchema);
    private static final List<Error> expectedMessage = schema.validate(validDevice.toString(), InputFormat.JSON);
    private static final List<Error> unexpectedMessage = schema.validate(invalidDevice.toString(), InputFormat.JSON);

    @BeforeEach
    void setUp() {
        stream = new StreamFunctionConfig(objectMapper, processingService, streamBridge);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void processing_shouldReturnMessage() throws Exception {
        when(processingService.validate(SCHEMA_ID, VERSION, validDevice))
                .thenReturn(expectedMessage);

        Message<@NonNull OutcomingPayload> message = stream.processing().apply(buildMessage(expectedPayload));

        assertThat(message).isNotNull();
        assertThat(message.getPayload()).isSameAs(expectedPayload);
        assertThat(message.getHeaders().get(KafkaHeaders.KEY)).isEqualTo(KEY);
        verifyNoInteractions(streamBridge);
    }

    @Test
    void processing_shouldThrowValidationException() throws Exception {
        when(processingService.validate(SCHEMA_ID, VERSION, invalidDevice))
                .thenReturn(unexpectedMessage);

        Message<@NonNull OutcomingPayload> message = stream.processing().apply(buildMessage(unexpectedPayload));
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        assertThat(message).isNull();
        verify(streamBridge).send(eq(FAILED_BINDING), captor.capture());

        Message<String> failedPayloadMessage = captor.getValue();
        JsonNode failedPayload = objectMapper.readValue(failedPayloadMessage.getPayload(), JsonNode.class);

        assertThat(failedPayload.get("traceId").asString())
                .isEqualTo(unexpectedPayload.getTraceId());
        assertThat(failedPayload.get("exceptionClass").asString())
                .isEqualTo("com.flexi.common.exception.service.ValidationException");
        assertThat(failedPayload.get("exceptionMessage").asString())
                .contains("/type");
    }

    @Test
    void processing_shouldThrowJsonSchemaNotFoundException() throws Exception {
        JsonSchemaNotFoundException schemaNotFoundException = new JsonSchemaNotFoundException(SCHEMA_ID, VERSION);
        when(processingService.validate(SCHEMA_ID, VERSION, validDevice))
                .thenThrow(schemaNotFoundException);

        Message<@NonNull OutcomingPayload> message = stream.processing().apply(buildMessage(expectedPayload));
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        assertThat(message).isNull();
        verify(streamBridge).send(eq(FAILED_BINDING), captor.capture());

        Message<String> failedPayloadMessage = captor.getValue();
        JsonNode failedPayload = objectMapper.readValue(failedPayloadMessage.getPayload(), JsonNode.class);
        assertThat(failedPayload.get("traceId").asString())
                .isEqualTo(unexpectedPayload.getTraceId());
        assertThat(failedPayload.get("exceptionClass").asString())
                .isEqualTo("com.flexi.common.exception.registry.JsonSchemaNotFoundException");
    }
}