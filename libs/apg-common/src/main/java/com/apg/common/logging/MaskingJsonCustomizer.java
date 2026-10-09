package com.apg.common.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Masks every string value of a structured (JSON) log line: message, stack trace, MDC values. Enabled in logback-spring.xml. */
public class MaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {
    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasker::mask));
    }
}
