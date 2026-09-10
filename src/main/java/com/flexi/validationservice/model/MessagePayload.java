package com.flexi.validationservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessagePayload {
    private String actionName;
    private String traceId;
    private String schemaId;
    private int schemaVersion;
    private JsonNode data;
}
