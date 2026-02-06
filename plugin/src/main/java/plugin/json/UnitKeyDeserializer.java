package plugin.json;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import mindustry.Vars;
import mindustry.type.UnitType;

import java.io.IOException;

public class UnitKeyDeserializer extends KeyDeserializer {

    @Override
    public UnitType deserializeKey(String key, DeserializationContext ctxt)
            throws IOException {

        UnitType unit = Vars.content.unit(key);

        if (unit == null) {
            throw new IllegalArgumentException("Unknown UnitType key: " + key);
        }

        return unit;
    }
}
