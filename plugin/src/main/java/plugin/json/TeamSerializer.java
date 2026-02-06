package plugin.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import mindustry.game.Team;

import java.io.IOException;

public class TeamSerializer extends JsonSerializer<Team> {
    @Override
    public void serialize(Team value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeNumber(value.id);
    }
}
