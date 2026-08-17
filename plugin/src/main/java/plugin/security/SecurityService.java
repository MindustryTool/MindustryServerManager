package plugin.security;

import plugin.session.SessionService;

import plugin.utils.Tr;

import lombok.RequiredArgsConstructor;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;
import mindustry.world.blocks.logic.CanvasBlock;
import mindustry.world.blocks.logic.LogicDisplay;
import plugin.Cfg;
import plugin.annotations.PlayerActionFilter;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class SecurityService {

    @PlayerActionFilter
    Boolean onlyAllowLoggedUserToUseLogic(PlayerAction action, SessionService sessionService) {
        if (action.type == ActionType.placeBlock && action.block != null
                && (action.block instanceof LogicDisplay || action.block instanceof CanvasBlock)) {
            var session = sessionService.get(action.player).orElse(null);

            if (session != null && session.isLoggedIn()) {
                return true;
            }

            action.player.sendMessage(Tr.t(action.player, "security.login_logic"));

            return false;
        }

        return true;
    }
}
