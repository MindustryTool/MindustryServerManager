package server.utils;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import dto.MapDto;
import dto.ModDto;
import dto.ModMetaDto;
import mindustry.core.Version;
import mindustry.io.MapIO;
import mindustry.mod.Mods.ModMeta;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class Utils {

    public static final ObjectMapper objectMapper = new ObjectMapper()//
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)//
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)//
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)//
            .findAndRegisterModules();

    public static final Json json = new Json();

    public static byte[] toByteArray(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
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

    public static <T> T readJsonAsClass(JsonNode data, Class<T> clazz) {
        try {
            return objectMapper.treeToValue(data, clazz);
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

    public static ModDto loadMod(Fi sourceFile) {
        try {

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

                return new ModDto()//
                        .setFilename(sourceFile.absolutePath())//
                        .setName(meta.name)
                        .setMeta(new ModMetaDto()//
                                .setAuthor(meta.author)//
                                .setDependencies(meta.dependencies.list())
                                .setDescription(meta.description)
                                .setDisplayName(meta.displayName)
                                .setHidden(meta.hidden)
                                .setInternalName(meta.internalName)
                                .setJava(meta.java)
                                .setMain(meta.main)
                                .setMinGameVersion(meta.minGameVersion)
                                .setName(meta.name)
                                .setRepo(meta.repo)
                                .setSubtitle(meta.subtitle)
                                .setVersion(meta.version));
            } catch (Exception e) {
                if (e instanceof ApiError) {
                    throw e;
                }
                // delete root zip file so it can be closed on windows
                if (rootZip != null)
                    rootZip.delete();
                throw new RuntimeException("Can not load mod from: " + sourceFile.name(), e);
            }
        } catch (Exception error) {
            Log.err("Can not load mod from: " + sourceFile.name() + ", " + error.getMessage());

            return new ModDto()
                    .setFilename(sourceFile.absolutePath())//
                    .setName("Error")
                    .setMeta(new ModMetaDto()//
                            .setAuthor("Error")
                            .setName("Error")
                            .setDisplayName("Error"));
        }
    }

    public static boolean isModFile(Fi file) {
        return file.extension().equalsIgnoreCase("jar") || file.extension().equalsIgnoreCase("zip");
    }

    public static boolean isMapFile(Fi file) {
        return file.extension().equalsIgnoreCase("msav");
    }

    public static MapDto loadMap(Fi baseFolder, Fi file) {
        mindustry.maps.Map map = null;

        try {
            map = MapIO.createMap(file, true);
        } catch (Throwable e) {
            Log.err("Can not read map data: " + e.getMessage());
            map = new mindustry.maps.Map(file, 0, 0, new StringMap(), true, 0, Version.build);
        }

        return new MapDto()//
                .setName(map.name())//
                .setFilename(map.file.absolutePath())
                .setCustom(map.custom)
                .setHeight(map.height)
                .setWidth(map.width);
    }

    public static String toReadableString(Duration duration) {
        long seconds = duration.getSeconds();

        long days = seconds / 86400;
        seconds %= 86400;

        long hours = seconds / 3600;
        seconds %= 3600;

        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0)
            sb.append(days).append("d ");
        if (hours > 0)
            sb.append(hours).append("h ");
        if (minutes > 0)
            sb.append(minutes).append("m ");
        if (seconds > 0)
            sb.append(seconds).append("s ");

        String result = sb.toString().trim();
        return result.isEmpty() ? "0s" : result;
    }

    public static boolean isConnectionException(Throwable e) {
        if (e == null) {
            return false;
        }

        return e instanceof ConnectException || isConnectionException(e.getCause());
    }

    public static <T> Mono<T> wrapError(Mono<T> publisher, Duration timeout, String message) {
        return publisher
                .onErrorMap(WebClientRequestException.class, error -> {
                    if (error.getCause() instanceof UnknownHostException) {
                        return new ApiError(HttpStatus.NOT_FOUND, "Server not found: " + error.getMessage());
                    }

                    if (isConnectionException(error.getCause())) {
                        return new ApiError(HttpStatus.BAD_REQUEST,
                                "Can not connect to server: " + error.getMessage());
                    }

                    return error;
                })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class,
                        error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout error: " + message));
    }

    public static <T> Flux<T> wrapError(Flux<T> publisher, Duration timeout, String message) {
        return publisher
                .onErrorMap(WebClientRequestException.class, error -> {
                    if (error.getCause() instanceof UnknownHostException) {
                        return new ApiError(HttpStatus.NOT_FOUND, "Server not found");
                    }

                    return error;
                })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class,
                        error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout error: " + message));
    }

    public static boolean handleStatus(HttpStatusCode status) {
        return switch (HttpStatus.valueOf(status.value())) {
            case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_ENTITY, CONFLICT -> true;
            default -> false;
        };
    }

    public static Mono<Throwable> createError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(message -> new ApiError(HttpStatus.valueOf(response.statusCode().value()), message));
    }

    public static BufferedImage fromBytes(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "internal-server-error", e);
        }
    }

    public static BufferedImage toPreviewImage(BufferedImage image) {
        try {

            var size = Math.max(image.getWidth(), image.getHeight());

            var width = image.getWidth() * 360 / size;
            var height = image.getHeight() * 360 / size;

            var scaledSize = new Dimension(width, height);
            var resizedImage = new BufferedImage(
                    (int) scaledSize.getWidth(),
                    (int) scaledSize.getHeight(),
                    BufferedImage.TYPE_INT_ARGB//
            );

            Graphics2D g = resizedImage.createGraphics();

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            g.drawImage(image, 0, 0, (int) scaledSize.getWidth(), (int) scaledSize.getHeight(), null);
            g.dispose();

            return resizedImage;
        } catch (Exception e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "internal-server-error", e);
        }
    }
}
