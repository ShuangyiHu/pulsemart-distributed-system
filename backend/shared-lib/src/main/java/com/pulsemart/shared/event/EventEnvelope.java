package com.pulsemart.shared.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventEnvelope {

    private UUID eventId;
    private String eventType;
    private String traceId;
    private String spanId;
    private Instant timestamp;
    private String sourceService;
    private String version;

    /**
     * Serialized JSON string of the event-specific payload.
     * Kept as Object so callers can pass any payload POJO;
     * Jackson serializes/deserializes transparently.
     */
    private Object payload;
}
