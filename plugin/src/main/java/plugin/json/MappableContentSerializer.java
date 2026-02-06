package plugin.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import mindustry.type.UnitType;

import java.io.IOException;

public class MappableContentSerializer extends JsonSerializer<UnitType> {

    @Override
    public void serialize(UnitType value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeString(value.name);
    }
}
