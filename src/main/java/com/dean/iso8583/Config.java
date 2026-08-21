package com.dean.iso8583;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring application configuration.
 */
@Configuration
public class Config {

    /**
     * Provides a globally shared {@link ObjectMapper} with all Jackson modules registered.
     *
     * <p>{@link ObjectMapper#findAndRegisterModules()} performs classpath scanning to discover
     * and auto-register available Jackson extension modules, most importantly
     * {@code jackson-datatype-jsr310} (JavaTimeModule), which enables correct
     * serialization and deserialization of {@code java.time} types such as
     * {@link java.time.Instant}, {@link java.time.LocalDate}, and {@link java.time.ZonedDateTime}.
     * Without this, these types would either serialize as arrays or throw an exception.</p>
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }
}
