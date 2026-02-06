package plugin.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import plugin.utils.TimeUtils;

import java.io.IOException;
import java.time.Duration;

public class DurationDeserializer extends JsonDeserializer<Duration> {

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        String value = p.getValueAsString();
        return TimeUtils.parse(value);
    }
}
