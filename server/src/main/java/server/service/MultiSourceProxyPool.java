package server.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import arc.util.Log;
import server.service.proxy.GeonodeSource;
import server.service.proxy.HProxySource;
import server.service.proxy.ProxyScrapeSource;
import server.service.proxy.ProxySource;

public class MultiSourceProxyPool {
    private static final int LOW_WATERMARK = 3;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final Duration REFRESH_DEBOUNCE = Duration.ofMinutes(2);

    private final List<ProxySource> sources;
    private final Set<TrackedProxy> proxies = new LinkedHashSet<>();
    private final ThreadLocal<InetSocketAddress> boundProxy = new ThreadLocal<>();
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private volatile Instant lastRefreshTime = Instant.MIN;

    public MultiSourceProxyPool() {
        this(new ProxyScrapeSource(), new GeonodeSource(), new HProxySource());
    }

    public MultiSourceProxyPool(ProxySource... sources) {
        this(sources != null ? List.of(sources) : List.of());
    }

    public MultiSourceProxyPool(List<ProxySource> sources) {
        this.sources = sources != null ? new ArrayList<>(sources) : new ArrayList<>();
        checkAndTriggerRefresh();
    }

    public synchronized void addProxies(List<InetSocketAddress> addresses) {
        if (addresses == null)
            return;
        for (InetSocketAddress address : addresses) {
            if (address != null) {
                proxies.add(new TrackedProxy(address));
            }
        }
    }

    public synchronized int size() {
        return proxies.size();
    }

    public List<ProxySource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    /**
     * Picks the usable proxy with the smallest sent count (first in list order on ties),
     * increments its sent count, and binds its address to the calling thread so the proxied
     * client uses exactly the tracked proxy. Callers MUST call {@link #release()} when done.
     *
     * @return Picked proxy, or null when no usable proxy exists.
     */
    public TrackedProxy acquire() {
        TrackedProxy picked;
        synchronized (this) {
            checkAndTriggerRefresh();

            picked = null;
            int bestSent = Integer.MAX_VALUE;
            for (TrackedProxy proxy : proxies) {
                if (proxy.getSentCount() < bestSent) {
                    bestSent = proxy.getSentCount();
                    picked = proxy;
                }
            }

            if (picked != null) {
                picked.markSent();
            }
        }
        boundProxy.set(picked != null ? picked.address() : null);
        return picked;
    }

    public void release() {
        boundProxy.remove();
    }

    public synchronized void recordSuccess(TrackedProxy proxy) {
        if (proxy == null || !proxies.contains(proxy)) {
            return;
        }
        proxy.recordSuccess();
    }

    public synchronized void recordFailure(TrackedProxy proxy) {
        if (proxy == null || !proxies.contains(proxy)) {
            return;
        }
        if (proxy.recordFailure() >= MAX_CONSECUTIVE_FAILURES) {
            evict(proxy);
        }
    }

    public synchronized void evict(TrackedProxy proxy) {
        if (proxy != null && proxies.remove(proxy)) {
            Log.debug("Evicted dead proxy: @:@ (remaining: @)",
                    proxy.address().getHostString(), proxy.address().getPort(), proxies.size());
        }
    }

    /**
     * @return ProxySelector that resolves to the proxy bound via {@link #acquire()} on the
     *         calling thread, or {@link Proxy#NO_PROXY} when nothing is bound.
     */
    public ProxySelector asProxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                InetSocketAddress target = boundProxy.get();
                if (target == null) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, target));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // Failures are recorded by the acquire/release caller; avoid double counting.
            }
        };
    }

    public synchronized void checkAndTriggerRefresh() {
        if (proxies.size() <= LOW_WATERMARK) {
            scheduleRefresh();
        }
    }

    private void scheduleRefresh() {
        Instant now = Instant.now();
        if (!now.isAfter(lastRefreshTime.plus(REFRESH_DEBOUNCE))) {
            return;
        }
        if (isRefreshing.compareAndSet(false, true)) {
            lastRefreshTime = now;
            CompletableFuture.runAsync(this::refreshPool);
        }
    }

    public void refreshPool() {
        try {
            Log.info("Refreshing multi-source proxy pool from @ sources...", sources.size());
            List<InetSocketAddress> fetched = new ArrayList<>();

            for (ProxySource source : sources) {
                try {
                    List<InetSocketAddress> fetchedFromSource = source.fetch();
                    if (fetchedFromSource != null) {
                        fetched.addAll(fetchedFromSource);
                        if (!fetchedFromSource.isEmpty()) {
                            Log.info("Add @ proxies from @", fetchedFromSource.size(), source.name());
                        } else {
                            Log.warn("No proxy added from @", source.name());
                        }
                    }
                } catch (Exception e) {
                    Log.warn("Failed fetching proxies from @: @", source.name(), e.getMessage());
                }
            }

            Collections.shuffle(fetched);
            addProxies(fetched);
            Log.info("Proxy pool refreshed. Total active proxies: @", size());
        } finally {
            isRefreshing.set(false);
        }
    }

    synchronized TrackedProxy getTracked(InetSocketAddress address) {
        for (TrackedProxy proxy : proxies) {
            if (proxy.address().equals(address)) {
                return proxy;
            }
        }
        return null;
    }
}
