package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The API specification is a deliverable, so it is tested like code: it must parse, every reference must resolve,
 * every operation must say who may call it and what it returns, and a schema property may only use real OpenAPI
 * keywords. The last rule catches the classic YAML slip where a comma inside an unquoted flow-style description
 * silently turns the rest of the sentence into a bogus key.
 */
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("docs", "api", "openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Set<String> SCHEMA_KEYWORDS = Set.of("type", "format", "description", "enum", "minimum",
            "maximum", "minLength", "maxLength", "minItems", "maxItems", "items", "properties", "required",
            "default", "oneOf", "additionalProperties", "example", "pattern", "$ref");

    @Test
    void theSpecificationParsesAsYaml() throws IOException {
        Map<String, Object> spec = load();

        assertEquals("3.1.0", spec.get("openapi"));
        assertTrue(map(spec.get("paths")).size() >= 7, "ingestion and reconciliation paths are documented");
    }

    @Test
    void everyReferenceResolvesToADefinedComponent() throws IOException {
        Map<String, Object> spec = load();
        List<String> refs = new ArrayList<>();
        collectRefs(spec, refs);

        assertTrue(refs.size() > 10, "the spec uses references");
        for (String ref : refs) {
            assertTrue(ref.startsWith("#/"), ref);
            Object node = spec;
            for (String part : ref.substring(2).split("/")) {
                node = map(node).get(part);
                assertTrue(node != null, "unresolved reference " + ref);
            }
        }
    }

    @Test
    void everyOperationDeclaresItsResponsesAndWhoMayCallIt() throws IOException {
        Map<String, Object> paths = map(load().get("paths"));

        for (Map.Entry<String, Object> path : paths.entrySet()) {
            for (Map.Entry<String, Object> operation : map(path.getValue()).entrySet()) {
                if (!METHODS.contains(operation.getKey())) {
                    continue;
                }
                String where = operation.getKey().toUpperCase() + " " + path.getKey();
                Map<String, Object> details = map(operation.getValue());
                assertTrue(details.containsKey("responses"), where + " has no responses");
                assertTrue(details.containsKey("x-required-roles"), where + " does not say who may call it");
                assertTrue(details.containsKey("summary"), where + " has no summary");
            }
        }
    }

    @Test
    void schemaPropertiesUseOnlyOpenApiKeywords() throws IOException {
        Map<String, Object> schemas = map(map(load().get("components")).get("schemas"));

        for (Map.Entry<String, Object> schema : schemas.entrySet()) {
            checkSchema(schema.getKey(), schema.getValue());
        }
    }

    private static void checkSchema(String where, Object node) {
        if (!(node instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> schema = map(node);
        for (String keyword : schema.keySet()) {
            assertTrue(SCHEMA_KEYWORDS.contains(keyword), where + " uses an unknown keyword: " + keyword);
        }
        if (schema.get("properties") != null) {
            for (Map.Entry<String, Object> property : map(schema.get("properties")).entrySet()) {
                checkSchema(where + "." + property.getKey(), property.getValue());
            }
        }
        checkSchema(where + "[]", schema.get("items"));
        if (schema.get("oneOf") instanceof List<?> options) {
            for (int i = 0; i < options.size(); i++) {
                checkSchema(where + ".oneOf[" + i + "]", options.get(i));
            }
        }
    }

    private static void collectRefs(Object node, List<String> refs) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref) {
                    refs.add(ref);
                } else {
                    collectRefs(entry.getValue(), refs);
                }
            }
        } else if (node instanceof List<?> list) {
            list.forEach(item -> collectRefs(item, refs));
        }
    }

    private static Map<String, Object> load() throws IOException {
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            return map(new Yaml().load(reader));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertTrue(node instanceof Map<?, ?>, "expected a mapping but found " + node);
        return (Map<String, Object>) node;
    }
}