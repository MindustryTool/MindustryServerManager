package server.service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import arc.util.Log;
import server.service.proxy.GeonodeSource;
import server.service.proxy.HProxySource;
import server.service.proxy.ProxyScrapeSource;
import server.service.proxy.ProxySource;

public class MultiSourceProxyPool {

    // TODO: Better rotation logic
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(2);

    private final List<ProxySource> sources;
    private final Queue<InetSocketAddress> pool = new ConcurrentLinkedQueue<>();
    private final Set<InetSocketAddress> activeSet = ConcurrentHashMap.newKeySet();
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
    }

    public synchronized void addProxies(List<InetSocketAddress> proxies) {
        if (proxies == null)
            return;
        for (InetSocketAddress proxy : proxies) {
            if (proxy != null && activeSet.add(proxy)) {
                pool.offer(proxy);
            }
        }
    }

    public int size() {
        return activeSet.size();
    }

    public List<ProxySource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public java.net.InetSocketAddress getNextCandidate() {
        checkAndTriggerRefresh();

        InetSocketAddress candidate = pool.poll();
        if (candidate != null) {
            pool.offer(candidate);
        }
        return candidate;
    }

    public void evict(InetSocketAddress proxy) {
        if (proxy != null && activeSet.remove(proxy)) {
            pool.remove(proxy);
            Log.debug("Evicted dead proxy: @:@ (remaining: @)", proxy.getHostString(), proxy.getPort(),
                    activeSet.size());
        }
    }

    /**
     * @return Dynamic ProxySelector backed by this proxy pool.
     */
    public ProxySelector asProxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                InetSocketAddress candidate = getNextCandidate();
                if (candidate == null) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, candidate));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
                if (sa instanceof InetSocketAddress inet) {
                    evict(inet);
                }
            }
        };
    }

    public void checkAndTriggerRefresh() {
        if (activeSet.isEmpty() || Instant.now().isAfter(lastRefreshTime.plus(REFRESH_INTERVAL))) {
            if (isRefreshing.compareAndSet(false, true)) {
                CompletableFuture.runAsync(this::refreshPool);
            }
        }
    }

    public void refreshPool() {
        try {
            Log.info("Refreshing multi-source proxy pool from @ sources...", sources.size());
            List<InetSocketAddress> fetched = new ArrayList<>();

            for (ProxySource source : sources) {
                try {
                    List<InetSocketAddress> proxies = source.fetch();
                    if (proxies != null) {
                        fetched.addAll(proxies);
                        if (proxies.size() > 0) {
                            Log.info("Add " + proxies.size() + " proxies from " + source.name());
                        } else {
                            Log.warn("No proxy added from " + source.name());
                        }
                    }
                } catch (Exception e) {
                    Log.warn("Failed fetching proxies from @: @", source.name(), e.getMessage());
                }
            }

            Collections.shuffle(fetched);
            addProxies(fetched);
            lastRefreshTime = Instant.now();
            Log.info("Proxy pool refreshed. Total active proxies: @", activeSet.size());
        } finally {
            isRefreshing.set(false);
        }
    }
}
