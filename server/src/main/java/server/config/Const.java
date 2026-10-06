package server.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import arc.files.Fi;
import arc.util.Log;

import org.modelmapper.ModelMapper;

public class Const {

    public static final String ENV = System.getenv("ENV");

    public static final boolean IS_DEVELOPMENT = ENV != null && ENV.equals("DEV");
    public static final boolean IS_PRODUCTION = !IS_DEVELOPMENT;

    public static final int DEFAULT_MINDUSTRY_SERVER_PORT = 6567;
    public static final int MAXIMUM_MINDUSTRY_SERVER_PORT = 20000;

    public static final String volumeFolderPath = getVolumeFolderPath();
    public static final String serverLabelName = "com.mindustry-tool.server.v2";
    public static final String serverIdLabel = "com.mindustry-tool.server.id.v2";
    public static final String API_URL = resolveApiBaseUrl();
    public static final File volumeFolder = new File(volumeFolderPath);
    public static final Fi serverFolder = new Fi(volumeFolderPath).child("servers");

    public static final String MANAGER_VERSION = "0.0.1";
    public static final Long MAX_FILE_SIZE = 5000000l;

    public static final ExecutorService executorService = Executors.newCachedThreadPool();

    private static final ModelMapper modelMapper = new ModelMapper();
    private static final ObjectMapper objectMapper = createObjectMapper();

    public static ModelMapper modelMapper() {
        return modelMapper;
    }

    public static String getVolumeFolderPath() {
        String path = System.getenv("SERVER_MANAGER_DATA");
        
        if (path == null) {
            path = "./data";
        }

        try {
            Log.info("Volume folder path: " + Paths.get(path).toRealPath());
        } catch (IOException e) {
            Log.err("Failed to resolve volume folder path: " + path, e);
        }

        return path;
    }

    public static String resolveApiBaseUrl() {
        String value = System.getenv("API_BASE_URL");
        if (value == null || value.isBlank()) {
            return "https://api.mindustry-tool.com/api/v4/";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    public static String resolvePluginGatewayUrl() {
        return resolvePluginGatewayUrl(System.getenv("PLUGIN_GATEWAY_URL"), IS_DEVELOPMENT);
    }

    public static String resolvePluginGatewayUrl(String envValue, boolean isDevelopment) {
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return isDevelopment ? "ws://server-manager:8088/gateway"
                : "ws://server.mindustry-tool.com:8089/gateway";
    }

    public static String resolveNodeDataHostPath() {
        return resolveNodeDataHostPath(volumeFolderPath, System.getenv("NODE_DATA_HOST_PATH"));
    }

    public static String resolveNodeDataHostPath(String containerPath, String envValue) {
        if (envValue == null || envValue.isBlank()) {
            return containerPath;
        }
        String normalized = envValue.trim().replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private static ObjectMapper createObjectMapper() {
        JavaTimeModule module = new JavaTimeModule();

        return new ObjectMapper(new JsonFactoryBuilder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(100 * 1024 * 1024).build())
                .configure(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION, false).build())
                .configure(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .registerModule(module);
    }
}
