package plugin.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import mindustry.gen.Unit;

import java.io.IOException;

public class UnitSerializer extends JsonSerializer<Unit> {
    @Override
    public void serialize(Unit value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeNumber(value.id);
    }
}
