package plugin.json;

import arc.struct.Seq;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

@SuppressWarnings("rawtypes")
public class SeqSerializer extends JsonSerializer<Seq> {
    @Override
    public void serialize(Seq value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartArray();
        for (Object o : value) {
            gen.writeObject(o);
        }
        gen.writeEndArray();
    }
}
