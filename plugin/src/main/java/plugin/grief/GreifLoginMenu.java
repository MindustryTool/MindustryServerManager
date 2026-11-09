package plugin.grief;

import mindustry.gen.Call;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
public class GreifLoginMenu extends ServerMenu<String> {

    @Override
    public void build(MenuContext<String> ctx) {
        String url = ctx.getState();
        this.title = Tr.t(ctx.getSession(), "grief.login_title");

        ctx.label(Tr.t(ctx.getSession(), "grief.login_prompt"));
        ctx.row();

        ctx.table(actions -> {
            String loginAct = ctx.registerAction((sess, st, res) -> {
                if (url != null && !url.isEmpty()) {
                    Call.openURI(sess.player.con, url);
                }
            });

            actions.add(UiBuilder.button("[green]" + Tr.t(ctx.getSession(), "grief.login"))
                .clicked(loginAct)
                .growX()
                .height(36f)
                .pad(4f));
        }).growX();
    }
}
