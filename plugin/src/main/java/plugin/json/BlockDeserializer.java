package plugin.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import mindustry.Vars;
import mindustry.world.Block;

import java.io.IOException;

public class BlockDeserializer extends JsonDeserializer<Block> {

    @Override
    public Block deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        String name = p.getValueAsString();

        if (name == null || name.isEmpty()) {
            return null;
        }

        Block unit = Vars.content.block(name);

        if (unit == null) {
            throw new IllegalArgumentException("Unknown UnitType: " + name);
        }

        return unit;
    }
}
