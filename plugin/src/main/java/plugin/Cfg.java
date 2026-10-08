package plugin;

import lombok.NoArgsConstructor;
import mindustry.Vars;
import plugin.annotations.Condition;
import plugin.annotations.Configuration;
import plugin.utils.JsonUtils;
import arc.files.Fi;
import arc.util.Log;
import common.server.ServerConfigMessage;

@Configuration("config.json")
@NoArgsConstructor
public class Cfg {

    public static class OnHub implements Condition {
        @Override
        public boolean check() {
            return IS_HUB;
        }
    }

    public static class OnOfficial implements Condition {
        @Override
        public boolean check() {
            return IS_OFFICIAL;
        }
    }

    public static final String PLUGIN_VERSION = "0.0.1";

    public static final String HUB = System.getenv("IS_HUB");
    public static final boolean IS_HUB = HUB != null && HUB.equals("true");

    public static final String OFFICIAL = System.getenv("IS_OFFICIAL");
    public static final boolean IS_OFFICIAL = OFFICIAL != null && OFFICIAL.equals("true");

    public static final String ENV = System.getenv("ENV");

    public static final boolean IS_DEVELOPMENT = ENV != null && ENV.equals("DEV");

    public static final String PLUGIN_GATEWAY_URL_ENV = "PLUGIN_GATEWAY_URL";
    public static final String DEV_GATEWAY_URL = "ws://server-manager:8088/gateway";
    public static final String PROD_GATEWAY_URL = "ws://server.mindustry-tool.com:8089/gateway";

    public static final String SERVER_IP = "103.20.96.24";
    public static final String DISCORD_INVITE_URL = "https://mindustry-tool.com/links/mindustry-tool";
    public static final String MINDUSTRY_TOOL_URL = "https://mindustry-tool.com";
    public static final String RULE_URL = MINDUSTRY_TOOL_URL + "/rules";
    public static final String GITHUB_URL = "https://github.com/MindustryTool/MindustryToolMod";

    public static final int MAX_IDENTICAL_IPS = 3;

    public static final int COLOR_NAME_LEVEL = 10;
    public static final int GRIEF_REPORT_COOLDOWN = 60;

    public static ServerConfigMessage serverConfig() {
        try {
            Log.info("Reading server.json");
            Fi file = Vars.dataDirectory.child("server.json");
            if (!file.exists()) {
                return null;
            }
            return JsonUtils.readJsonAsClass(file.readString(), ServerConfigMessage.class);
        } catch (Exception e) {
            Log.warn("Failed to read server.json", e);
            return null;
        }
    }

    public static String webSocketAuthToken() {
        ServerConfigMessage serverConfig = serverConfig();
        return serverConfig != null ? serverConfig.getJwt() : null;
    }

    public static String serverId() {
        return System.getenv("SERVER_ID");
    }

    public static String gatewayUrl() {
        return resolveGatewayUrl(System.getenv(PLUGIN_GATEWAY_URL_ENV), IS_DEVELOPMENT);
    }

    public static String resolveGatewayUrl(String envValue, boolean isDevelopment) {
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return isDevelopment ? DEV_GATEWAY_URL : PROD_GATEWAY_URL;
    }
}
