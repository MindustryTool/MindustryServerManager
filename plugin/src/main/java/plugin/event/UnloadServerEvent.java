package plugin.event;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UnloadServerEvent {
    public final boolean restart;
    public final Runnable beforeUnload;

    public UnloadServerEvent(boolean restart) {
        this(restart, null);
    }
}
