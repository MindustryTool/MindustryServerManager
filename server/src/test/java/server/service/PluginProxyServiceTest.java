package server.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dto.PluginQueryDto;
import dto.PluginVersionDto;
import server.utils.ApiError;
import server.utils.Utils;

import static org.junit.jupiter.api.Assertions.*;

public class PluginProxyServiceTest {

    @Test
    public void testVersionCacheHitAndMiss() {
        Cache<String, PluginVersionDto> versionCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        Cache<String, byte[]> binaryCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();

        AtomicInteger fetchCount = new AtomicInteger(0);

        PluginProxyService service = new PluginProxyService(null, versionCache, binaryCache) {
            @Override
            public PluginVersionDto getPluginVersion(PluginQueryDto query) {
                String key = buildCacheKey(query);
                return versionCache.get(key, k -> {
                    fetchCount.incrementAndGet();
                    return new PluginVersionDto("2026-10-05T00:00:00Z");
                });
            }
        };

        PluginQueryDto query = new PluginQueryDto("MindustryTool", "MindustryServerManager", "plugin");

        // 1st call -> miss, fetch count becomes 1
        PluginVersionDto v1 = service.getPluginVersion(query);
        assertNotNull(v1);
        assertEquals("2026-10-05T00:00:00Z", v1.getUpdatedAt());
        assertEquals(1, fetchCount.get());

        // 2nd call -> hit, fetch count remains 1
        PluginVersionDto v2 = service.getPluginVersion(query);
        assertNotNull(v2);
        assertEquals("2026-10-05T00:00:00Z", v2.getUpdatedAt());
        assertEquals(1, fetchCount.get());
    }

    @Test
    public void testBinaryCacheHitAndMiss() {
        Cache<String, PluginVersionDto> versionCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        Cache<String, byte[]> binaryCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();

        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[] sampleJar = "sample-jar-binary-bytes".getBytes(StandardCharsets.UTF_8);

        PluginProxyService service = new PluginProxyService(null, versionCache, binaryCache) {
            @Override
            public byte[] downloadPlugin(PluginQueryDto query) {
                String key = buildCacheKey(query);
                return binaryCache.get(key, k -> {
                    fetchCount.incrementAndGet();
                    return sampleJar;
                });
            }
        };

        PluginQueryDto query = new PluginQueryDto("MindustryTool", "MindustryServerManager", "plugin");

        // 1st call -> miss
        byte[] b1 = service.downloadPlugin(query);
        assertArrayEquals(sampleJar, b1);
        assertEquals(1, fetchCount.get());

        // 2nd call -> hit
        byte[] b2 = service.downloadPlugin(query);
        assertArrayEquals(sampleJar, b2);
        assertEquals(1, fetchCount.get());
    }

    @Test
    public void testCacheExpiration() throws InterruptedException {
        // Cache that expires after 50 milliseconds
        Cache<String, PluginVersionDto> versionCache = Caffeine.newBuilder()
                .expireAfterWrite(50, TimeUnit.MILLISECONDS)
                .build();
        Cache<String, byte[]> binaryCache = Caffeine.newBuilder()
                .expireAfterWrite(50, TimeUnit.MILLISECONDS)
                .build();

        AtomicInteger fetchCount = new AtomicInteger(0);

        PluginProxyService service = new PluginProxyService(null, versionCache, binaryCache) {
            @Override
            public PluginVersionDto getPluginVersion(PluginQueryDto query) {
                String key = buildCacheKey(query);
                return versionCache.get(key, k -> {
                    fetchCount.incrementAndGet();
                    return new PluginVersionDto("version-" + fetchCount.get());
                });
            }
        };

        PluginQueryDto query = new PluginQueryDto("MindustryTool", "MindustryServerManager", "plugin");

        PluginVersionDto v1 = service.getPluginVersion(query);
        assertEquals("version-1", v1.getUpdatedAt());
        assertEquals(1, fetchCount.get());

        Thread.sleep(70);

        PluginVersionDto v2 = service.getPluginVersion(query);
        assertEquals("version-2", v2.getUpdatedAt());
        assertEquals(2, fetchCount.get());
    }

    @Test
    public void testNullOrInvalidQueryValidation() {
        PluginProxyService service = new PluginProxyService();

        assertThrows(ApiError.class, () -> service.getPluginVersion(null));
        assertThrows(ApiError.class, () -> service.getPluginVersion(new PluginQueryDto(null, "repo", "tag")));
        assertThrows(ApiError.class, () -> service.downloadPlugin(null));
        assertThrows(ApiError.class, () -> service.downloadPlugin(new PluginQueryDto("owner", null, "tag")));
    }

    @Test
    public void testSerializationOfPluginDtos() {
        PluginQueryDto query = new PluginQueryDto("owner", "repo", "tag");
        String jsonQuery = Utils.toJsonString(query);
        PluginQueryDto deserializedQuery = Utils.readJsonAsClass(jsonQuery, PluginQueryDto.class);

        assertEquals("owner", deserializedQuery.getOwner());
        assertEquals("repo", deserializedQuery.getRepo());
        assertEquals("tag", deserializedQuery.getTag());

        PluginVersionDto version = new PluginVersionDto("2026-10-05T01:00:00Z");
        String jsonVersion = Utils.toJsonString(version);
        PluginVersionDto deserializedVersion = Utils.readJsonAsClass(jsonVersion, PluginVersionDto.class);

        assertEquals("2026-10-05T01:00:00Z", deserializedVersion.getUpdatedAt());
    }
}
