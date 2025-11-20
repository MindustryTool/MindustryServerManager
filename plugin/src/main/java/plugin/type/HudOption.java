package plugin.type;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class HudOption {
    private final PlayerPressCallback callback;
    private final String text;
}
