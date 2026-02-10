package plugin.utils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import arc.struct.Seq;
import mindustry.ctype.MappableContent;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Block;
import plugin.json.BlockDeserializer;
import plugin.json.DurationDeserializer;
import plugin.json.DurationSerializer;
import plugin.json.MappableContentSerializer;
import plugin.json.SeqDeserializer;
import plugin.json.SeqSerializer;
import plugin.json.TeamDeserializer;
import plugin.json.TeamSerializer;
import plugin.json.UnitDeserializer;
import plugin.json.UnitSerializer;
import plugin.json.UnitTypeDeserializer;

public class JsonUtils {

    private static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .registerModule(new SimpleModule()
                    .addDeserializer(Seq.class, new SeqDeserializer())
                    .addSerializer(Seq.class, new SeqSerializer())
                    .addDeserializer(Block.class, new BlockDeserializer())
                    .addDeserializer(Duration.class, new DurationDeserializer())
                    .addSerializer(Duration.class, new DurationSerializer())
                    .addDeserializer(Team.class, new TeamDeserializer())
                    .addSerializer(Team.class, new TeamSerializer())
                    .addSerializer(MappableContent.class, new MappableContentSerializer())
                    .addSerializer(Unit.class, new UnitSerializer())
                    .addDeserializer(Unit.class, new UnitDeserializer())
                    .addDeserializer(UnitType.class, new UnitTypeDeserializer()))
            .registerModule(new JavaTimeModule());

    public static String toJsonString(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (IOException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static <T> T readJsonAsClass(String data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static ObjectNode createObjectNode() {
        return objectMapper.createObjectNode();
    }

    public static <T> List<T> readJsonAsArrayClass(String data, Class<T> clazz) {
        try {
            return objectMapper.readerForListOf(clazz).readValue(data);
        } catch (Exception e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static <T> T readJsonAsClass(String data, TypeReference<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static Object readJson(String data, java.lang.reflect.Type type) {
        try {
            return objectMapper.readValue(data, objectMapper.getTypeFactory().constructType(type));
        } catch (Exception e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static JsonNode readJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }
}
