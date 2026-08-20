package com.dean.iso8583.core.spec;

import com.dean.iso8583.core.dto.IsoDTOs;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Developer Note:
 * <p>Enterprise Dynamic ISO 8583 Packager Spec Registry.</p>
 * <p>Automatically scans and loads JSON packager specification definitions from classpath:specs/*.json.</p>
 *
 * <p>In an enterprise payment gateway, different acquiring hosts and card networks <br>(e.g. Visa SMS, Mastercard IPM,
 * AS2805, APACS) require different field dictionaries, length rules, and padding strategies.</p>
 * <p>This registry allows payment switches to dynamically lookup or register packager dialects at runtime.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsoSpecRegistry {

    private final ObjectMapper objectMapper;

    public static final String DEFAULT_SPEC_ID = "iso8583-1987";
    private static final String SPEC_LOCATION = "classpath*:specs/*.json";
    private final Map<String, IsoDTOs.IsoSpecDefinition> specMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadClasspathSpecs();
    }

    /**
     * Scans the classpath for ISO 8583 specification files and
     * registers them in memory.
     *
     * <p>Specifications are expected under:</p>
     *
     * <pre>
     *  classpath:{@code specs/*.json}
     * </pre>
     */
    public void loadClasspathSpecs() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(SPEC_LOCATION);

            for (Resource resource : resources) {
                loadSpec(resource);
            }

            log.info("Loaded {} ISO 8583 specification(s)", specMap.size());

        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan ISO 8583 specification directory: %s"
                    .formatted(SPEC_LOCATION), e);
        }
    }

    private void loadSpec(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {

            IsoDTOs.IsoSpecDefinition spec = objectMapper.readValue(inputStream, IsoDTOs.IsoSpecDefinition.class);

            registerSpec(spec);

            int fieldCount = spec.fields() == null ? 0 : spec.fields().size();

            log.info("Loaded ISO 8583 spec: {} ({}) with {} field definitions", spec.id(), spec.name(), fieldCount);

        } catch (IOException e) {
            log.error("Failed to load ISO 8583 spec from resource {}", resource.getFilename(), e);
        }
    }

    /**
     * Registers an ISO 8583 specification.
     *
     * @param spec specification to register
     */
    public void registerSpec(IsoDTOs.IsoSpecDefinition spec) {
        Objects.requireNonNull(spec, "spec cannot be null");

        Objects.requireNonNull(spec.id(), "spec.id cannot be null");

        String specId = normalizeSpecId(spec.id());

        specMap.put(specId, spec);
    }

    /**
     * Retrieves an ISO 8583 specification by ID.
     *
     * <p>If the supplied ID is null, blank, or unknown,
     * the default specification is returned.</p>
     *
     * @param specId specification identifier
     * @return matching specification or the default specification
     */
    public IsoDTOs.IsoSpecDefinition getSpec(String specId) {

        if (specId == null || specId.isBlank()) {
            return getDefaultSpec();
        }

        String normalizedId = normalizeSpecId(specId);

        IsoDTOs.IsoSpecDefinition spec = specMap.get(normalizedId);

        if (spec == null) {
            log.warn("ISO 8583 spec '{}' not found. Falling back to '{}'", specId, DEFAULT_SPEC_ID);

            return getDefaultSpec();
        }

        return spec;
    }

    /**
     * Returns the default ISO 8583:1987 specification.
     *
     * @throws IllegalStateException if the default specification
     *                               has not been registered
     */
    public IsoDTOs.IsoSpecDefinition getDefaultSpec() {
        IsoDTOs.IsoSpecDefinition defaultSpec = specMap.get(DEFAULT_SPEC_ID);

        if (defaultSpec == null) {
            throw new IllegalStateException("Default ISO 8583 spec '%s' is not registered".formatted(DEFAULT_SPEC_ID));
        }

        return defaultSpec;
    }

    /**
     * Returns all registered specifications.
     *
     * @return unmodifiable view of registered specifications
     */
    public Map<String, IsoDTOs.IsoSpecDefinition> getAllSpecs() {
        return Collections.unmodifiableMap(specMap);
    }

    private static String normalizeSpecId(String specId) {
        return specId.trim().toLowerCase(Locale.ROOT);
    }
}
