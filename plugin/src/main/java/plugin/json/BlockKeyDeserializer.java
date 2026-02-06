package plugin.json;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import mindustry.Vars;

import java.io.IOException;

public class BlockKeyDeserializer extends KeyDeserializer {

    @Override
    public mindustry.world.Block deserializeKey(String key, DeserializationContext ctxt)
            throws IOException {

        mindustry.world.Block unit = Vars.content.block(key);

        if (unit == null) {
            throw new IllegalArgumentException("Unknown mindustry.world.Block key: " + key);
        }

        return unit;
    }
}
