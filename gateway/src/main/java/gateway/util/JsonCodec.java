package gateway.util;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonCodec {

    private JsonCodec() {
    }

    public static Object deserialize(ObjectMapper mapper, JsonNode payload, Class<?> clazz, String what) {
        if (clazz == null || clazz == Void.class || clazz == Void.TYPE) {
            return null;
        }
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        if (clazz == JsonNode.class) {
            return payload;
        }
        try {
            return mapper.treeToValue(payload, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot deserialize " + what + " to " + clazz.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <Res> Res convert(ObjectMapper mapper, JsonNode node, Class<Res> responseType) {
        if (responseType == null || responseType == Void.class || responseType == Void.TYPE) {
            return null;
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (responseType == JsonNode.class) {
            return (Res) node;
        }
        if (responseType == byte[].class && node.isBinary()) {
            try {
                return (Res) node.binaryValue();
            } catch (IOException e) {
                throw new RuntimeException("Cannot read binary RPC payload: " + e.getMessage(), e);
            }
        }
        if (responseType == String.class && node.isTextual()) {
            return (Res) node.asText();
        }
        try {
            return mapper.treeToValue(node, responseType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Cannot convert RPC payload to " + responseType.getName() + ": " + e.getMessage(), e);
        }
    }
}
