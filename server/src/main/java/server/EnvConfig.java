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
        String securityKey,
        String dataFolder,
        String serverUrl
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
                getEnv("SECURITY_KEY_V2", null),
                getEnv("DATA_FOLDER", null),
                getEnv("SERVER_URL", "http://api:8080")
            )
        );
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
