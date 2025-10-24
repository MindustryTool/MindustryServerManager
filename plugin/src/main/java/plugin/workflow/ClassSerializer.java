package plugin.workflow;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

public class ClassSerializer extends StdSerializer<Class> {

    public ClassSerializer() {
        this(Class.class);
    }

    public ClassSerializer(Class t) {
        super(t);
    }

    @Override
    public void serialize(Class value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();

        for (var field : value.getDeclaredFields()) {
            gen.writeStringField(field.getName(), field.getType().getName());
        }

        gen.writeEndObject();

    }
}
