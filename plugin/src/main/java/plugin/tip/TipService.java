package plugin.tip;

import plugin.utils.Tr;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import arc.func.Func;
import arc.struct.Seq;
import mindustry.gen.Iconc;
import plugin.Cfg;
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
        tips.add((locale) -> Tr.t(locale, "tip.powered_by"));
        tips.add((locale) -> Tr.t(locale, "tip.discord"));
        tips.add((locale) -> Tr.t(locale, "tip.vnw"));
        tips.add((locale) -> Tr.t(locale, "tip.rtv"));
        tips.add((locale) -> Tr.t(locale, "tip.me"));
        tips.add((locale) -> Tr.t(locale, "tip.grief"));
        tips.add((locale) -> Tr.t(locale, "tip.website"));
        tips.add((locale) -> Tr.t(locale, "tip.respect"));
        tips.add((locale) -> Tr.t(locale, "tip.update"));
        tips.add((locale) -> Tr.t(locale, "tip.star", "url", Cfg.GITHUB_URL));
        tips.add((locale) -> Tr.t(locale, "tip.be_respectful"));
        tips.add((locale) -> Tr.t(locale, "tip.report_griefers"));
        tips.add((locale) -> Tr.t(locale, "tip.ask_admins"));
        tips.add((locale) -> "[white]" + Iconc.blockRouter + "Router chains");
        tips.add((locale) -> Tr.t(locale, "tip.have_fun"));
        tips.add((locale) -> "The factory must grow!!!");
        tips.add((locale) -> Tr.t(locale, "tip.level_color", "level", Cfg.COLOR_NAME_LEVEL));
        tips.add((locale) -> Tr.t(locale, "tip.bugs"));
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
