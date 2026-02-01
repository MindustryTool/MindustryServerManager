package plugin;

public class Config {

    public static final String PLUGIN_VERSION = "0.0.1";

    public static final String HUB = System.getenv("IS_HUB");
    public static final boolean IS_HUB = HUB != null && HUB.equals("true");

    public static final String ENV = System.getenv("ENV");

    public static final boolean IS_DEVELOPMENT = ENV != null && ENV.equals("DEV");

    public static final String SERVER_IP = "103.20.96.24";
    public static final String DISCORD_INVITE_URL = "https://discord.gg/Jx5qfU2xmC";
    public static final String MINDUSTRY_TOOL_URL = "https://mindustry-tool.com";
    public static final String RULE_URL = MINDUSTRY_TOOL_URL + "/rules";
    public static final String GITHUB_URL = "https://github.com/MindustryTool/MindustryToolMod";

    public static final int MAX_IDENTICAL_IPS = 3;
    public static final String HUB_MESSAGE = """
            Command
            [yellow]/servers[white] to show server list
            [yellow]/rtv[white] to vote for changing map
            [yellow]/maps[white] to see map list
            [yellow]/hub[white] show this
            [green]Log in to get more feature
            """;

    public static final String CHOOSE_SERVER_MESSAGE = """
            [accent]Click[] [orange]any server data[] to [lime]play[]
            [accent]Click[] to [scarlet]offline server[] to [lime]starting & play[] this.
            """;

    public static final String WELCOME_MESSAGE = """
            [accent]Welcome to the server!
            [lightgray]Play fair, have fun, and enjoy your stay.
            """;

    public static final int COLOR_NAME_LEVEL = 10;
    public static final int GRIEF_REPORT_COOLDOWN = 60;
}
