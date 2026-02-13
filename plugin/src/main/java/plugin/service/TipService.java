package plugin.service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import arc.func.Func;
import arc.struct.Seq;
import mindustry.gen.Iconc;
import plugin.Config;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Schedule;
import plugin.utils.Utils;

@Component
public class TipService {

    private final Seq<Func<Locale, String>> tips = new Seq<>();

    @Init
    private void init() {
        tips.add((locale) -> I18n.t(locale, "@Powered by", " MindustryTool"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/discord[sky] ",
                "@to join our Discord server"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/vnw[sky] ", "@to skip a wave"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/rtv[sky] ", "@to change map"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/me[sky] ", "@to see your stats"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/grief[sky] ", "@to report a player"));
        tips.add((locale) -> I18n.t(locale, "@Use", " [accent]/website[sky] ",
                "@to visit our website for schematics and maps"));
        tips.add((locale) -> I18n.t(locale, "@Remember to respect other players"));
        tips.add((locale) -> I18n.t(locale, "@Remember to download and update", " MindustryTool"));
        tips.add((locale) -> I18n.t(locale, "@If you find this helpful please give us a star: ",
                Config.GITHUB_URL));
        tips.add((locale) -> I18n.t(locale, "@Be respectful — toxic behavior may lead to punishment"));
        tips.add((locale) -> I18n.t(locale, "@Report griefers instead of arguing in chat"));
        tips.add((locale) -> I18n.t(locale, "@Admins are here to help — ask nicely"));
        tips.add((locale) -> "[white]" + Iconc.blockRouter + "Router chains");
        tips.add((locale) -> I18n.t(locale, "@Have fun!!!"));
        tips.add((locale) -> "The factory must grow!!!");
        tips.add((locale) -> I18n.t(locale, "@Reach level", " ", Config.COLOR_NAME_LEVEL, " ",
                "@to unlock colored name"));
    }

    public void registerTip(Func<Locale, String> tip) {
        tips.add(tip);
    }

    @Schedule(fixedDelay = 3, unit = TimeUnit.MINUTES)
    private void sendTips() {
        var tip = tips.random();

        Tasks.io("Send tip", () -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                for (var player : players) {
                    player.sendMessage("\n[sky]" + tip.get(locale) + "[white]\n");
                }
            });
        });
    }
}
