package com.sporthub.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Standard technical error envelope shared by SportHub HTTP services. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private int status;
    private String code;
    private String message;

    @Builder.Default
    private Instant timestamp = Instant.now();

    private String traceId;
    private List<FieldError> fieldErrors;
    private Map<String, Object> details;
}
