package server;

public record EnvConfig(
    DockerEnv docker,
    ServerConfig serverConfig
) {
    public record DockerEnv(
        String mindustryServerImage,
        String serverDataFolder,
        String authToken,
        String username
    ) {}

    public record ServerConfig(
        Boolean autoPortAssign,
        String accessToken,
        String dataFolder,
        String backendWsUrl,
        String apiBaseUrl
    ) {}

    public static EnvConfig load() {
        return new EnvConfig(
            new DockerEnv(
                getEnv("MINDUSTRY_SERVER_IMAGE", "ghcr.io/mindustrytool/mindustry-server-v7b146:latest"),
                getEnv("SERVER_DATA_FOLDER", null),
                getEnv("DOCKER_AUTH_TOKEN", null),
                getEnv("DOCKER_USERNAME", null)
            ),
            new ServerConfig(
                Boolean.parseBoolean(getEnv("AUTO_PORT_ASSIGN", "true")),
                getEnv("ACCESS_TOKEN_v2", null),
                getEnv("DATA_FOLDER", null),
                getEnv("BACKEND_WS_URL", "wss://api.mindustry-tool.com/managers/gateway"),
                getEnv("API_BASE_URL", "https://api.mindustry-tool.com/api/v4/")
            )
        );
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
