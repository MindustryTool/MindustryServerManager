package plugin.gamemode.catali;

import lombok.RequiredArgsConstructor;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.annotations.Init;
import plugin.utils.Tr;
import plugin.tip.TipService;

@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
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
