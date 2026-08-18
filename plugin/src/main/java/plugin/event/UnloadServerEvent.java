package plugin.event;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UnloadServerEvent {
    public final boolean exit;
}
