package plugin.session;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class LoginMenu extends ServerMenu<Void> {

    private final LoginService loginService;

    @Override
    public void build(MenuContext<Void> ctx) {
        this.title = Tr.t(ctx.getSession(), "login.menu_title");

        ctx.label(Tr.t(ctx.getSession(), "login.menu_desc"));
        ctx.row();

        ctx.table(actions -> {
            String openAct = ctx.registerAction((sess, st, res) -> {
                var link = loginService.getOrGenerateLink(sess.player);
                if (link != null && link.url != null && !link.url.isEmpty()) {
                    Call.openURI(sess.player.con, link.url);
                    sess.player.sendMessage(Tr.t(sess, "login.link_opened"));
                } else {
                    sess.player.sendMessage(Tr.t(sess, "login.link_expired"));
                }
            });

            actions.add(UiBuilder.button("[green]" + Tr.t(ctx.getSession(), "login.btn_open"))
                .clicked(openAct)
                .width(MenuContext.SAFE_CARD_WIDTH)
                .height(36f)
                .pad(4f));
        });
    }
}
