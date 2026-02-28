package plugin.service;

import lombok.RequiredArgsConstructor;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;
import mindustry.world.blocks.logic.LogicBlock;
import plugin.Cfg;
import plugin.annotations.PlayerActionFilter;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class SecurityService {

    @PlayerActionFilter
    Boolean onlyAllowLoggedUserToUseLogic(PlayerAction action, SessionHandler sessionService) {
        if (action.type == ActionType.placeBlock && action.block != null && action.block instanceof LogicBlock) {
            var session = sessionService.get(action.player).orElse(null);

            if (session != null && session.isLoggedIn()) {
                return true;
            }

            action.player.sendMessage(I18n.t(action.player, "@Login first to use logic block, use /login to login"));

            return false;
        }

        return true;
    }
}
