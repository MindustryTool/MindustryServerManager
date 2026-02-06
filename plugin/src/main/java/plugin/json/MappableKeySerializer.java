package plugin.json;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import mindustry.ctype.MappableContent;

import java.io.IOException;

public class MappableKeySerializer extends JsonSerializer<MappableContent> {

    @Override
    public void serialize(MappableContent value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        gen.writeFieldName(value.name);
    }
}
