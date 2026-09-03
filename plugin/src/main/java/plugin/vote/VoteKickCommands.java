package plugin.vote;

import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class VoteKickCommands {

    private final VoteKickService voteKickService;

    @ClientCommand(name = "votekick", description = "Vote to kick a player with a valid reason.", admin = false)
    public void votekick(Session session,
                         @Param(name = "player", required = false) String targetName,
                         @Param(name = "reason", required = false, variadic = true) String[] reasonWords) {
        if (voteKickService.isVoting()) {
            new VotePromptMenu().send(session, null);
            return;
        }

        if (targetName == null || targetName.trim().isEmpty()) {
            new VoteKickMenu().send(session, null);
            return;
        }

        Player found;
        if (targetName.length() > 1 && targetName.startsWith("#") && Strings.canParseInt(targetName.substring(1))) {
            int id = Strings.parseInt(targetName.substring(1));
            found = Groups.player.find(p -> p.id() == id);
        } else {
            found = Groups.player.find(p -> p.name.equalsIgnoreCase(targetName)
                    || Strings.stripColors(p.name).equalsIgnoreCase(Strings.stripColors(targetName)));
        }

        if (found == null) {
            session.player.sendMessage(Tr.t(session, "votekick.no_player_found", "name", targetName));
            return;
        }

        if (reasonWords == null || reasonWords.length == 0) {
            new VoteKickMenu().send(session, found);
            return;
        }

        String reason = String.join(" ", reasonWords).trim();
        voteKickService.startVote(session.player, found, reason);
    }

    @ClientCommand(name = "vote", description = "Vote to kick the current player. Admins can cancel with 'c'.", admin = false)
    public void vote(Session session, @Param(name = "choice", required = false) String choice) {
        if (!voteKickService.isVoting()) {
            session.player.sendMessage(Tr.t(session, "votekick.no_vote_in_progress"));
            return;
        }

        if (choice == null || choice.trim().isEmpty()) {
            new VotePromptMenu().send(session, null);
            return;
        }

        if (session.player.admin && choice.equalsIgnoreCase("c")) {
            voteKickService.cancelByAdmin(session.player);
            return;
        }

        int sign = switch (choice.toLowerCase()) {
            case "y", "yes" -> 1;
            case "n", "no" -> -1;
            default -> 0;
        };

        if (sign == 0) {
            session.player.sendMessage(Tr.t(session, "votekick.invalid_vote"));
            return;
        }

        voteKickService.vote(session.player, sign);
    }
}
