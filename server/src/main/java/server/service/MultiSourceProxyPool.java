package server.service;

import java.net.InetSocketAddress;
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

    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(15);

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
        if (proxies == null) return;
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

    public InetSocketAddress getNextCandidate() {
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
            Log.debug("Evicted dead proxy: @:@ (remaining: @)", proxy.getHostString(), proxy.getPort(), activeSet.size());
        }
    }

    /**
     * @return Dynamic java.net.ProxySelector backed by this proxy pool.
     */
    public java.net.ProxySelector asProxySelector() {
        return new java.net.ProxySelector() {
            @Override
            public List<java.net.Proxy> select(java.net.URI uri) {
                InetSocketAddress candidate = getNextCandidate();
                if (candidate == null) {
                    return List.of(java.net.Proxy.NO_PROXY);
                }
                return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP, candidate));
            }

            @Override
            public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
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
