package com.dean.iso8583.core.spec;

import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * Developer Note:
 * Enterprise Dynamic ISO 8583 Packager Spec Registry.
 * Automatically scans and loads JSON packager specification definitions from classpath:specs/*.json.
 * 
 * In an enterprise payment gateway, different acquiring hosts and card networks (e.g. Visa SMS, Mastercard IPM,
 * AS2805, APACS) require different field dictionaries, length rules, and padding strategies.
 * This registry allows payment switches to dynamically lookup or register packager dialects at runtime.
 */
@Slf4j
@Component
public class IsoSpecRegistry {

    public static final String DEFAULT_SPEC_ID = "iso8583-1987";

    private final ObjectMapper objectMapper;
    private final Map<String, IsoSpecDefinition> specMap = new HashMap<>();

    public IsoSpecRegistry() {
        this(new ObjectMapper());
    }

    public IsoSpecRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        loadClasspathSpecs();
    }

    /**
     * Scans classpath:specs/*.json and loads packager definitions into memory.
     */
    public void loadClasspathSpecs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:specs/*.json");

            for (Resource resource : resources) {
                try (InputStream inputStream = resource.getInputStream()) {
                    IsoSpecDefinition spec = objectMapper.readValue(inputStream, IsoSpecDefinition.class);
                    registerSpec(spec);
                    log.info("Successfully loaded ISO 8583 Packager Spec: {} ({}) with {} field definitions",
                            spec.id(), spec.name(), spec.fields() != null ? spec.fields().size() : 0);
                } catch (Exception e) {
                    log.error("Failed to load ISO 8583 spec from resource {}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error scanning ISO 8583 spec directory classpath:specs/", e);
        }
    }

    /**
     * Registers a packager specification definition dynamically.
     */
    public void registerSpec(IsoSpecDefinition spec) {
        Objects.requireNonNull(spec, "spec cannot be null");
        Objects.requireNonNull(spec.id(), "spec.id cannot be null");
        specMap.put(spec.id().toLowerCase(Locale.ROOT), spec);
    }

    /**
     * Retrieves an IsoSpecDefinition by ID.
     * Falls back to DEFAULT_SPEC_ID ("iso8583-1987") if specified ID is null or not found.
     */
    public IsoSpecDefinition getSpec(String specId) {
        if (specId == null || specId.isBlank()) {
            return getDefaultSpec();
        }
        IsoSpecDefinition spec = specMap.get(specId.toLowerCase(Locale.ROOT));
        if (spec == null) {
            log.warn("ISO 8583 Spec '{}' not found. Falling back to default '{}'", specId, DEFAULT_SPEC_ID);
            return getDefaultSpec();
        }
        return spec;
    }

    /**
     * Returns the default ISO 8583:1987 specification definition.
     */
    public IsoSpecDefinition getDefaultSpec() {
        IsoSpecDefinition defaultSpec = specMap.get(DEFAULT_SPEC_ID);
        if (defaultSpec == null) {
            throw new IllegalStateException("Default ISO 8583 spec 'iso8583-1987' is not registered");
        }
        return defaultSpec;
    }

    /**
     * Returns an unmodifiable map of all registered packager specifications.
     */
    public Map<String, IsoSpecDefinition> getAllSpecs() {
        return Collections.unmodifiableMap(specMap);
    }
}
