package plugin.event;

public class UserBannedEvent implements ModerationEvent {

    public final String userId;
    public final String reason;
    public final String actor;
    public final long timestamp;

    public UserBannedEvent(String userId, String reason, String actor) {
        this.userId = userId;
        this.reason = reason;
        this.actor = actor;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String actor() {
        return actor;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }
}
