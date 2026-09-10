package com.flexi.validationservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.flexi.validationservice.exception.ValidationException;
import com.flexi.validationservice.model.FailedPayload;
import com.flexi.validationservice.model.MessagePayload;
import com.flexi.validationservice.service.ProcessingService;
import com.networknt.schema.Error;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class StreamFunctionConfig {

    private static final String FAILED_BINDING = "failed-out-0";

    private final ObjectMapper objectMapper;
    private final ProcessingService processingService;
    private final StreamBridge streamBridge;

    @Bean
    public Function<Message<String>, Message<String>> processing() {
        return message -> {
            String key = (String) message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
            try {
                MessagePayload payload = objectMapper.readValue(message.getPayload(), MessagePayload.class);
                MDC.put("traceId", payload.getTraceId());
                MDC.put("schemaId", payload.getSchemaId());
                log.info("Start validate message: key='{}'", key);
                List<Error> validationMessages = processingService.validate(payload.getSchemaId(),
                        payload.getSchemaVersion(), payload.getData());
                if (validationMessages.isEmpty()) {
                    log.info("Success validate message: key='{}'", key);
                } else {
                    throw new ValidationException(validationMessages.toString());
                }
                return MessageBuilder
                        .withPayload(message.getPayload())
                        .setHeader(KafkaHeaders.KEY, key)
                        .build();
            } catch (Exception e) {
                log.warn("Failed validate message: key='{}', exceptionClass='{}', exceptionMessage='{}'", key,
                        e.getClass().getCanonicalName(), e.getLocalizedMessage());
                sendToFailed(new FailedPayload(
                        e.getClass().getCanonicalName(),
                        e.getLocalizedMessage()), key);
                return null;
            } finally {
                MDC.clear();
            }
        };
    }

    private void sendToFailed(FailedPayload failedPayload, String key) {
        try {
            Message<String> failedMessage = MessageBuilder
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