package plugin.session;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LevelUpEvent {
    public final Session session;
    public final int lastLevel;
    public final int newLevel;
}
