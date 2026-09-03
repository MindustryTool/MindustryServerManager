package plugin.vote;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.session.LoginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class VoteKickMenu extends PluginMenu<Player> {

    @Override
    public void build(Session session, Player target) {
        if (!session.isLoggedIn()) {
            session.player.sendMessage(Tr.t(session, "votekick.login_required"));
            new LoginMenu().send(session, null);
            return;
        }

        this.title = Tr.t(session, "votekick.menu_title");

        if (target == null) {
            this.description = Tr.t(session, "votekick.menu_select_player");

            Seq<Player> candidates = new Seq<>();
            for (Player p : Groups.player) {
                if (p != session.player && !p.admin && !p.isLocal()
                        && (!Vars.state.rules.pvp || p.team() == session.player.team())) {
                    candidates.add(p);
                }
            }

            for (Player candidate : candidates) {
                String optionText = "[lightgray]" + candidate.name + " [accent](#" + candidate.id() + ")";
                option(optionText, (s, st) -> {
                    new VoteKickMenu().send(s, candidate);
                });
                row();
            }

            text(Tr.t(session, "votekick.btn_close"));
        } else {
            this.description = Tr.t(session, "votekick.menu_select_reason", "target", target.name);

            String[] reasonKeys = {
                    "votekick.reason_griefing",
                    "votekick.reason_afk",
                    "votekick.reason_toxicity",
                    "votekick.reason_other"
            };

            for (String key : reasonKeys) {
                String reasonText = Tr.t(session, key);
                option(reasonText, (s, st) -> {
                    Registry.get(VoteKickService.class).startVote(s.player, target, reasonText);
                });
                row();
            }

            option(Tr.t(session, "votekick.btn_back"), (s, st) -> {
                new VoteKickMenu().send(s, null);
            });
            row();

            text(Tr.t(session, "votekick.btn_close"));
        }
    }
}
