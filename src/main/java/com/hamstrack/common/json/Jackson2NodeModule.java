package com.hamstrack.common.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Bridges Jackson 2 tree nodes across the Spring MVC boundary.
 *
 * <p>Spring Boot 4 / Spring MVC serialize with Jackson 3 ({@code tools.jackson}),
 * but our JSONB-backed DTO and entity fields (custom field values, field-def
 * {@code config}) are typed as Jackson 2's
 * {@link com.fasterxml.jackson.databind.JsonNode} — the type Hibernate's JSON
 * mapper reads and writes. Jackson 3 has no built-in support for a Jackson 2
 * node: on read it treats the abstract type as a POJO and fails with
 * "Cannot construct instance of JsonNode (no Creators)"; on write it dumps the
 * node's getters ({@code {"array":false,...}}). Either way, any request or
 * response carrying custom fields 500s.
 *
 * <p>This module teaches the Jackson 3 mapper to (de)serialize Jackson 2
 * {@code JsonNode} values by round-tripping their raw JSON. It is picked up
 * automatically: Boot 4's {@code JacksonAutoConfiguration} registers every
 * {@link tools.jackson.databind.JacksonModule} bean into the MVC
 * {@code JsonMapper}.
 */
@Component
public class Jackson2NodeModule extends SimpleModule {

    /** Reused Jackson 2 mapper for parsing/printing legacy trees (thread-safe). */
    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    public Jackson2NodeModule() {
        super("Jackson2NodeCompat");
        addSerializer(com.fasterxml.jackson.databind.JsonNode.class, new Serializer());
        addDeserializer(com.fasterxml.jackson.databind.JsonNode.class, new Deserializer());
    }

    private static final class Serializer extends ValueSerializer<com.fasterxml.jackson.databind.JsonNode> {
        @Override
        public void serialize(com.fasterxml.jackson.databind.JsonNode value, JsonGenerator gen,
                              SerializationContext ctxt) {
            // JsonNode.toString() is valid JSON — write it through untouched.
            gen.writeRawValue(value.toString());
        }
    }

    private static final class Deserializer extends ValueDeserializer<com.fasterxml.jackson.databind.JsonNode> {
        @Override
        public com.fasterxml.jackson.databind.JsonNode deserialize(JsonParser p, DeserializationContext ctxt) {
            // Read the incoming subtree with Jackson 3, then re-parse it into a Jackson 2 node.
            String raw = ctxt.readTree(p).toString();
            try {
                return JACKSON2.readTree(raw);
            } catch (IOException e) {
                // raw came from an already-parsed tree, so this should never happen
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getNullValue(DeserializationContext ctxt) {
            // Match Jackson 2's native binding: JSON null -> NullNode (treated as "clear").
            return NullNode.getInstance();
        }
    }
}