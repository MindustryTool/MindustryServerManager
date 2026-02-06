package plugin.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import mindustry.game.Team;

import java.io.IOException;

public class TeamDeserializer extends JsonDeserializer<Team> {
    @Override
    public Team deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        int id = p.getIntValue();
        return Team.get(id);
    }
}
