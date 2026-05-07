package plugin.service;

import lombok.RequiredArgsConstructor;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class RankService {

}
