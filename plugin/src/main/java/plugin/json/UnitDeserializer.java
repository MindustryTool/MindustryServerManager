package plugin.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import mindustry.Vars;
import mindustry.type.UnitType;

import java.io.IOException;

public class UnitDeserializer extends JsonDeserializer<UnitType> {

    @Override
    public UnitType deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        String name = p.getValueAsString();

        if (name == null || name.isEmpty()) {
            return null;
        }

        UnitType unit = Vars.content.unit(name);

        if (unit == null) {
            throw new IllegalArgumentException("Unknown UnitType: " + name);
        }

        return unit;
    }
}
