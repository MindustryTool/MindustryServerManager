package plugin.workflow;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

public class ClassSerializer<T> extends StdSerializer<T> {

    public ClassSerializer(Class<T> t) {
        super(t);
    }

    @Override
    public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();

        if (value instanceof Class clazz) {
            for (var field : clazz.getDeclaredFields()) {
                gen.writeStringField(field.getName(), field.getType().getName());
            }
        }

        gen.writeEndObject();

    }
}
