package plugin.type;

public interface Trail {
    public boolean isAllowed(Session session);

    public void render(Session session, float x, float y);
}
