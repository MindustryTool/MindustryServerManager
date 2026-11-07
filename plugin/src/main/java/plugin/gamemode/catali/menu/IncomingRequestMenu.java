package plugin.gamemode.catali.menu;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliGamemode;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class IncomingRequestMenu extends ServerMenu<Player> {

    private final CataliGamemode gamemode;

    @Override
    public void build(MenuContext<Player> ctx) {
        Session session = ctx.getSession();
        Player requester = ctx.getState();
        Objects.requireNonNull(session);

        if (requester == null) {
            return;
        }

        var team = gamemode.findTeam(session.player);
        if (team == null) {
            return;
        }

        this.title = Tr.t(session, "catali.incoming_join_request");

        ctx.label("[lightgray]" + Tr.t(session, "catali.accept_reject_request"));
        ctx.row();

        var card = UiBuilder.table().pad(6f);
        card.width(MenuContext.SAFE_CARD_WIDTH);
        card.add(UiBuilder.label("[white]Player: [accent]" + requester.name() + "[]")).row();

        var btnBar = UiBuilder.table().pad(4f);

        String acceptAct = ctx.registerAction((sess, req, res) -> {
            team.joinRequests.remove(requester.uuid());
            team.members.add(requester.uuid());
            requester.team(team.team);
            requester.sendMessage(Tr.t(requester, "catali.join_accepted", "name", session.player.name));
            sess.player.sendMessage("[green]Accepted " + requester.name() + " into team.");
        });
        btnBar.add(UiBuilder.button("[green]" + Tr.t(session, "catali.accept") + "[]")
                .clicked(acceptAct)
                .width(105f)
                .height(35f)
                .pad(3f));

        String rejectAct = ctx.registerAction((sess, req, res) -> {
            team.joinRequests.remove(requester.uuid());
            requester.sendMessage(Tr.t(requester, "catali.join_rejected", "name", session.player.name));
            sess.player.sendMessage("[scarlet]Rejected " + requester.name() + "'s join request.");
        });
        btnBar.add(UiBuilder.button("[scarlet]" + Tr.t(session, "catali.reject") + "[]")
                .clicked(rejectAct)
                .width(105f)
                .height(35f)
                .pad(3f));

        card.add(btnBar);
        ctx.add(card);
    }
}
