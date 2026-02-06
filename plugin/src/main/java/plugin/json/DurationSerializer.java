package plugin.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import plugin.utils.TimeUtils;

import java.io.IOException;
import java.time.Duration;

public class DurationSerializer extends JsonSerializer<Duration> {

    @Override
    public void serialize(Duration duration, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        gen.writeString(TimeUtils.toString(duration));
    }
}
