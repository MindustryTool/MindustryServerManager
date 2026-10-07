package gateway.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Jsons {

    private Jsons() {
    }

    static Object deserialize(ObjectMapper mapper, JsonNode payload, Class<?> clazz, String what) {
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
}
