package plugin.event;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UnloadServerEvent {
    public final boolean exit;
    public final Runnable beforeUnload;

    public UnloadServerEvent(boolean exit) {
        this(exit, null);
    }
}
