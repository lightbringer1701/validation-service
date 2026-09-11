package com.flexi.validationservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.cloud.stream.binder.kafka.KafkaBinderMetrics;
import org.springframework.stereotype.Component;

@Component
class NoOpBindingMeters {
    NoOpBindingMeters(MeterRegistry registry) {
        registry.config().meterFilter(
                MeterFilter.denyNameStartsWith(KafkaBinderMetrics.OFFSET_LAG_METRIC_NAME));
    }
}