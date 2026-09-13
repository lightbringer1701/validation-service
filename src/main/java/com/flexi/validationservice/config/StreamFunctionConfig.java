package com.flexi.validationservice.config;

import com.flexi.common.exception.service.ValidationException;
import com.flexi.common.payload.FailedPayload;
import com.flexi.common.payload.OutcomingPayload;
import com.flexi.validationservice.service.ProcessingService;
import com.networknt.schema.Error;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class StreamFunctionConfig {

    private static final String FAILED_BINDING = "failed-out-0";

    private final ObjectMapper objectMapper;
    private final ProcessingService processingService;
    private final StreamBridge streamBridge;

    @Bean
    public Function<Message<@NonNull OutcomingPayload>, Message<@NonNull OutcomingPayload>> processing() {
        return message -> {
            String key = (String) message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
            OutcomingPayload payload = message.getPayload();
            try {
                MDC.put("traceId", payload.getTraceId());
                MDC.put("schemaId", payload.getSchemaId());
                log.info("Start validate message: key='{}'", key);

                List<Error> validationMessages = processingService.validate(payload.getSchemaId(),
                        payload.getSchemaVersion(), payload.getData());
                if (validationMessages.isEmpty()) {
                    log.info("Success validate message: key='{}'", key);
                } else {
                    throw new ValidationException(buildValidationExceptionMessage(validationMessages));
                }

                return MessageBuilder
                        .withPayload(payload)
                        .setHeader(KafkaHeaders.KEY, key)
                        .build();
            } catch (Exception e) {
                log.warn("Failed validate message: key='{}', exceptionClass='{}', exceptionMessage='{}'", key,
                        e.getClass().getCanonicalName(), e.getMessage());
                sendToFailed(new FailedPayload(
                        payload.getTraceId(),
                        e.getClass().getCanonicalName(),
                        e.getMessage()), key);
                return null;
            } finally {
                MDC.clear();
            }
        };
    }

    private String buildValidationExceptionMessage(List<Error> validationMessages) {
        return validationMessages.stream()
                .map(v -> v.getInstanceLocation() + ": " + v.getMessage())
                .collect(Collectors.joining("\n"));
    }

    private void sendToFailed(FailedPayload failedPayload, String key) {
        try {
            Message<@NonNull String> failedMessage = MessageBuilder
                    .withPayload(Objects.requireNonNull(objectMapper.writeValueAsString(failedPayload)))
                    .setHeader(KafkaHeaders.KEY, key)
                    .build();
            streamBridge.send(FAILED_BINDING, failedMessage);
            log.warn("Message sent to failed topic: key='{}', exception='{}'", key, failedPayload.getExceptionClass());
        } catch (Exception e) {
            log.error("CRITICAL failed to send to failed topic: ", e);
        }
    }
}