package plugin.event;

public interface ModerationEvent {

    String actor();

    String reason();

    long timestamp();
}
