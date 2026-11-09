package plugin.welcome;

import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.PluginMenuService;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
public class WelcomeMenu extends ServerMenu<Void> {

    @Override
    public void build(MenuContext<Void> ctx) {
        this.title = "[gold]MindustryTool";

        ctx.label(Tr.t(ctx.getSession(), "welcome.welcome_message"));
        ctx.row();

        ctx.table(links -> {
            String rulesAct = ctx.registerAction((sess, st, res) -> Call.openURI(sess.player.con, Cfg.RULE_URL));

            String webAct = ctx
                    .registerAction((sess, st, res) -> Call.openURI(sess.player.con, Cfg.MINDUSTRY_TOOL_URL));

            String discordAct = ctx
                    .registerAction((sess, st, res) -> Call.openURI(sess.player.con, Cfg.DISCORD_INVITE_URL));

            links.add(UiBuilder.button(Iconc.book + " [green]" + Tr.t(ctx.getSession(), "welcome.rules"))
                    .clicked(rulesAct).growX().height(36f).pad(3f)).row();

            links.add(UiBuilder.button(Iconc.link + " [green]" + Tr.t(ctx.getSession(), "welcome.website"))
                    .clicked(webAct).growX().height(36f).pad(3f)).row();

            links.add(UiBuilder.button(Iconc.discord + " [royal]" + Tr.t(ctx.getSession(), "welcome.discord"))
                    .clicked(discordAct).growX().height(36f).pad(3f)).row();

            String backAct = ctx.registerAction((sess, st, res) -> Registry.get(PluginMenuService.class).hide(sess.player));
            links.add(UiBuilder.button(Tr.t(ctx.getSession(), "menu.back"))
                    .clicked(backAct).growX().height(36f).pad(3f)).row();
        }).growX();
    }
}
