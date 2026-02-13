package plugin.gamemode.catali;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.service.I18n;
import plugin.service.TipService;

@Gamemode("catali")
@RequiredArgsConstructor
public class CataliTip {

    private final TipService tipService;

    @Init
    private void init() {
        tipService.registerTip((locale) -> I18n.t(locale, "@Use", "[accent]/u[white]", "@to upgrade your unit"));
        tipService.registerTip(
                (locale) -> I18n.t(locale, "@Use", "[accent]/a[white]", "@to abanto unit or disband team"));
        tipService.registerTip((locale) -> I18n.t(locale, "@Boss providea lot of exp, take your chances to kill it"));
    }
}
