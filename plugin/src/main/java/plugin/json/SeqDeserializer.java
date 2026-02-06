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

public class SeqDeserializer extends JsonDeserializer<Seq<?>> implements ContextualDeserializer {
    private JavaType valueType;

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
        JavaType wrapperType = property.getType();
        JavaType valueType = wrapperType.containedType(0);
        SeqDeserializer deserializer = new SeqDeserializer();
        deserializer.valueType = valueType;
        return deserializer;
    }

    @Override
    public Seq<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        List<?> list = ctxt.readValue(p, ctxt.getTypeFactory().constructCollectionType(List.class, valueType));
        return Seq.with(list);
    }
}
