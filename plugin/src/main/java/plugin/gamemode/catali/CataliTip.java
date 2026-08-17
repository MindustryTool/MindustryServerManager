package plugin.gamemode.catali;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.utils.Tr;
import plugin.tip.TipService;

@Gamemode("catali")
@RequiredArgsConstructor
public class CataliTip {

    private final TipService tipService;

    @Init
    private void init() {
        tipService.registerTip((locale) -> Tr.t(locale, "catali.tip_upgrade"));
        tipService.registerTip(
                (locale) -> Tr.t(locale, "catali.tip_abandon"));
        tipService.registerTip((locale) -> Tr.t(locale, "catali.tip_boss"));
    }
}
