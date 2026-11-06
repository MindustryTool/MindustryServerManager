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
        this.title = "Grief Check";

        ctx.label(Tr.t(ctx.getSession(), "grief.login_prompt"));
        ctx.row();

        ctx.table(actions -> {
            String loginAct = ctx.registerAction((sess, st, res) -> {
                if (url != null && !url.isEmpty()) {
                    Call.openURI(sess.player.con, url);
                }
            });
            String cancelAct = ctx.registerAction((sess, st, res) -> {});

            actions.add(UiBuilder.button(Tr.t(ctx.getSession(), "grief.login")).clicked(loginAct).width(120f).height(38f).pad(4f));
            actions.add(UiBuilder.button(Tr.t(ctx.getSession(), "grief.cancel")).clicked(cancelAct).width(120f).height(38f).pad(4f));
        });
    }
}
