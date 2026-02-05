package plugin;

public interface IComponent {
    default void init() {
    }

    default void destroy() {
    }
}
