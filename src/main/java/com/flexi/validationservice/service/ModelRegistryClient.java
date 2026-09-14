package com.flexi.validationservice.service;

import com.flexi.validationservice.config.RegistryInterceptor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import tools.jackson.databind.JsonNode;

@FeignClient(
        name = "model-registry",
        configuration = RegistryInterceptor.class
)
public interface ModelRegistryClient {
    @GetMapping("api/v1/validations/{id}/versions/{version}")
    JsonNode getSchema(@PathVariable String id, @PathVariable int version);
}
