package server.config;

import java.io.File;
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
import org.modelmapper.ModelMapper;

public class Const {

    public static final String ENV = System.getenv("ENV");

    public static final boolean IS_DEVELOPMENT = ENV != null && ENV.equals("DEV");
    public static final boolean IS_PRODUCTION = !IS_DEVELOPMENT;

    public static int DEFAULT_MINDUSTRY_SERVER_PORT = 6567;
    public static int MAXIMUM_MINDUSTRY_SERVER_PORT = 20000;

    public static String volumeFolderPath = getVolumeFolderPath();
    public static String serverLabelName = "com.mindustry-tool.server.v2";
    public static String serverIdLabel = "com.mindustry-tool.server.id.v2";
    public static String API_URL = "https://api.mindustry-tool.com/api/v4/";
    public static File volumeFolder = new File(volumeFolderPath);

    public static final String MANAGER_VERSION = "0.0.1";
    public static final Long MAX_FILE_SIZE = 5000000l;

    public static final ExecutorService executorService = Executors.newCachedThreadPool();

    private static final ModelMapper modelMapper = new ModelMapper();
    private static final ObjectMapper objectMapper = createObjectMapper();

    public static ModelMapper modelMapper() {
        return modelMapper;
    }

    public static String getVolumeFolderPath() {
        String path = IS_DEVELOPMENT ? "./data" : System.getenv("SERVER_MANAGER_DATA");

        if (path == null) {
            path = "./data";
        }

        return path;
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private static ObjectMapper createObjectMapper() {
        JavaTimeModule module = new JavaTimeModule();

        return new ObjectMapper(new JsonFactoryBuilder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
                .configure(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION, false).build())
                .configure(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .registerModule(module);
    }
}
