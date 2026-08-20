package plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import plugin.annotations.Destroy;
import plugin.annotations.Init;

public class RegistryDestroyOrderTest {

    private static final List<String> events = new ArrayList<>();

    @AfterEach
    void tearDown() {
        Registry.destroy();
        events.clear();
    }

    static class Leaf {
        @Destroy
        void destroy() {
            events.add("leaf");
        }
    }

    static class Middle {
        public final Leaf leaf;

        Middle(Leaf leaf) {
            this.leaf = leaf;
        }

        @Destroy
        void destroy() {
            events.add("middle");
        }
    }

    static class Root {
        public final Middle middle;

        Root(Middle middle) {
            this.middle = middle;
        }

        @Destroy
        void destroy() {
            events.add("root");
        }
    }

    static class LazyDependent {
        @Destroy
        void destroy() {
            events.add("lazyDependent");
        }
    }

    static class InitLazyDependent {
        @Init
        void init() {
            Registry.get(LazyLeaf.class);
        }

        @Destroy
        void destroy() {
            events.add("lazyDependent");
        }
    }

    static class LazyLeaf {
        @Destroy
        void destroy() {
            events.add("lazyLeaf");
        }
    }

    static class SiblingA {
        @Destroy
        void destroy() {
            events.add("siblingA");
        }
    }

    static class SiblingB {
        @Destroy
        void destroy() {
            events.add("siblingB");
        }
    }

    @Test
    void dependentsAreDestroyedBeforeDependencies() {
        Registry.get(Root.class);
        Registry.destroy();
        assertEquals(List.of("root", "middle", "leaf"), events);
    }

    @Test
    void runtimeLazyComponentsAreDestroyedBeforeAcquirers() {
        Registry.get(LazyDependent.class);
        Registry.get(LazyLeaf.class);
        Registry.destroy();
        assertEquals(List.of("lazyLeaf", "lazyDependent"), events);
    }

    @Test
    void initTimeLazyDependenciesAreDestroyedAfterAcquirers() {
        Registry.get(InitLazyDependent.class);
        Registry.destroy();
        assertEquals(List.of("lazyDependent", "lazyLeaf"), events);
    }

    @Test
    void siblingsAreDestroyedInReverseCreationOrder() {
        Registry.get(SiblingA.class);
        Registry.get(SiblingB.class);
        Registry.destroy();
        assertEquals(List.of("siblingB", "siblingA"), events);
    }

    @Test
    void orderIsDeterministicAcrossRuns() {
        Registry.get(Root.class);
        Registry.destroy();
        List<String> first = new ArrayList<>(events);
        events.clear();

        Registry.get(Root.class);
        Registry.destroy();
        assertEquals(first, events);
    }

    @Test
    void registryIsClearedAfterDestroy() {
        Registry.get(SiblingA.class);
        Registry.destroy();
        assertNull(Registry.getOrNull(SiblingA.class));
        assertTrue(Registry.getAll(Object.class).isEmpty());
    }

    @Test
    void getAllReturnsInstancesInCreationOrder() {
        SiblingA a = Registry.get(SiblingA.class);
        SiblingB b = Registry.get(SiblingB.class);
        assertEquals(List.of(a, b), Registry.getAll(Object.class));
    }
}
