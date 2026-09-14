package com.flexi.validationservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ModelRegistryService modelRegistryService;

    @InjectMocks
    private ProcessingService service;

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

    JsonNode exceptedDevice = objectMapper.readTree("""
        {
            "name": "test-device",
            "type": "WORKSTATION",
            "ipAddress": "10.10.10.10"
        }
    """);

    @Test
    void validate_shouldValid() {

    }
}