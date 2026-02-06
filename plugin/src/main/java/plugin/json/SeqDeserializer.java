package plugin.json;

import arc.struct.Seq;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;
import java.util.List;

public class SeqDeserializer extends JsonDeserializer<Seq<?>>
        implements ContextualDeserializer {

    private JavaType valueType;

    public SeqDeserializer() {
    }

    private SeqDeserializer(JavaType valueType) {
        this.valueType = valueType;
    }

    @Override
    public JsonDeserializer<?> createContextual(
            DeserializationContext ctxt,
            BeanProperty property) throws JsonMappingException {

        JavaType wrapperType;

        if (property != null) {
            // Field-based deserialization
            wrapperType = property.getType();
        } else {
            // Root / Map / generic deserialization
            wrapperType = ctxt.getContextualType();
        }

        if (wrapperType == null || wrapperType.containedTypeCount() == 0) {
            // No generic info → fallback
            return this;
        }

        JavaType valueType = wrapperType.containedType(0);
        return new SeqDeserializer(valueType);
    }

    @Override
    public Seq<?> deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        if (valueType == null) {
            throw JsonMappingException.from(p, "SeqDeserializer: valueType not resolved");
        }

        List<?> list = ctxt.readValue(
                p,
                ctxt.getTypeFactory()
                        .constructCollectionType(List.class, valueType));

        return Seq.with(list);
    }
}
