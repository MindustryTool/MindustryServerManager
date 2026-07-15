package plugin.gamemode.ziger;

import plugin.annotations.Configuration;

@Configuration("ziger/config.json")
public class ZigerConfig {
    public int targetItems = 8000;
    public int targetLiquids = 8000;
    public int thoriumMin = 10;
    public int thoriumTarget = 30;
}
