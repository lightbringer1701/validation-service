package com.flexi.validationservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailedPayload {
    private String exceptionClass;
    private String exceptionMessage;
}
