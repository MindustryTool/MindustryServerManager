package plugin.workflow;

@FunctionalInterface
public interface ESupplier<T> {

    T get() throws Exception;

}
