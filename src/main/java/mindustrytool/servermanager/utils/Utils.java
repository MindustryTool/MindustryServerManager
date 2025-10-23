package mindustrytool.servermanager.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.files.Fi;
import arc.files.ZipFi;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import mindustry.mod.Mods.ModMeta;
import reactor.core.publisher.Mono;

public class Utils {

    public static final ObjectMapper objectMapper = new ObjectMapper()//
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)//
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)//
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)//
            .findAndRegisterModules();

    public static final Json json = new Json();

    public static synchronized byte[] toByteArray(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "webp", baos);
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to write image to bytes");
        }
    }

    public static Mono<byte[]> readAllBytes(FilePart file) {
        return DataBufferUtils.join(file.content()).handle((buffer, sink) -> {
            try {
                sink.next(buffer.asInputStream().readAllBytes());
            } catch (Exception e) {
                sink.error(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read file", e));
            }
        });
    }

    public static String toJsonString(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static <T> T readJsonAsClass(String data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static JsonNode readFile(File file) {
        try {
            return objectMapper.readTree(file);
        } catch (IOException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static JsonNode readString(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static void writeObject(File file, String key, String value) throws IOException {
        if (file.isDirectory()) {
            deleteFileRecursive(file);
        }

        if (!file.exists()) {
            file.createNewFile();
        }

        var config = Utils.readFile(file);
        var original = config;

        var keys = key.split("\\.");

        for (int i = 0; i < keys.length - 1; i++) {
            var k = keys[i];
            if (config.has(k)) {
                config = config.get(k);
            } else {
                config = ((ObjectNode) config).set(k, Utils.readString("{}"));
                config = config.get(k);
            }
        }

        ((ObjectNode) config).set(keys[keys.length - 1], Utils.readString(value));

        Files.writeString(file.toPath(), Utils.toJsonString(original));
    }

    public static boolean deleteFileRecursive(File file) {
        if (!file.exists()) {
            return false;
        }

        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteFileRecursive(f);
            }
        }

        return file.delete();
    }

    public static ModMeta findMeta(Fi file) {
        Fi metaFile = null;

        var metaFiles = List.of("mod.json", "mod.hjson", "plugin.json", "plugin.hjson");
        for (String name : metaFiles) {
            if ((metaFile = file.child(name)).exists()) {
                break;
            }
        }

        if (!metaFile.exists()) {
            return null;
        }

        ModMeta meta = json.fromJson(ModMeta.class, Jval.read(metaFile.readString()).toString(Jformat.plain));
        meta.cleanup();
        return meta;
    }

    public static ModMeta loadMod(Fi sourceFile) throws Exception {
        ZipFi rootZip = null;

        try {
            Fi zip = sourceFile.isDirectory() ? sourceFile : (rootZip = new ZipFi(sourceFile));
            if (zip.list().length == 1 && zip.list()[0].isDirectory()) {
                zip = zip.list()[0];
            }

            ModMeta meta = findMeta(zip);

            if (meta == null) {
                Log.warn("Mod @ doesn't have a '[mod/plugin].[h]json' file, delete and skipping.", zip);
                sourceFile.delete();
                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid file: No mod.json found.");
            }

            return meta;
        } catch (Exception e) {
            if (e instanceof ApiError) {
                throw e;
            }
            // delete root zip file so it can be closed on windows
            if (rootZip != null)
                rootZip.delete();
            throw new RuntimeException("Can not load mod from: " + sourceFile.name(), e);
        }
    }

}
