package plugin.update;

import java.util.concurrent.TimeUnit;

import arc.util.Log;
import dto.PluginQueryDto;
import dto.PluginVersionDto;
import lombok.Data;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;

@Data
public class PluginData {
    private final String id;
    private final String path;
    private final String owner;
    private final String repo;
    private final String tag;

    public PluginData(String id, String path, String owner, String repo, String tag) {
        this.id = id;
        this.path = path;
        this.owner = owner;
        this.repo = repo;
        this.tag = tag;
    }

    public PluginVersion getPluginVersion() {
        ApiGateway gateway = Registry.getOrNull(ApiGateway.class);
        if (gateway == null || !gateway.isConnected()) {
            Log.warn("ApiGateway not connected, skipping plugin version check for @", this.id);
            return null;
        }

        try {
            PluginQueryDto query = new PluginQueryDto(this.owner, this.repo, this.tag);
            PluginVersionDto dto = gateway.sendRequest("get-plugin-version", query, PluginVersionDto.class)
                    .get(30, TimeUnit.SECONDS);

            if (dto == null) {
                return null;
            }

            PluginVersion version = new PluginVersion();
            version.setUpdatedAt(dto.getUpdatedAt());
            return version;
        } catch (Exception e) {
            throw new RuntimeException("Error while getting plugin version " + this.id, e);
        }
    }

    public byte[] download() {
        ApiGateway gateway = Registry.getOrNull(ApiGateway.class);
        if (gateway == null || !gateway.isConnected()) {
            throw new IllegalStateException("ApiGateway not connected, cannot download plugin: " + this.id);
        }

        try {
            PluginQueryDto query = new PluginQueryDto(this.owner, this.repo, this.tag);
            byte[] data = gateway.sendRequest("download-plugin", query, byte[].class)
                    .get(120, TimeUnit.SECONDS);

            if (data == null || data.length == 0) {
                throw new IllegalStateException("Received empty download response for plugin: " + this.id);
            }

            return data;
        } catch (Exception e) {
            throw new RuntimeException("Error while downloading plugin: " + this.id, e);
        }
    }

    @lombok.Data
    public static class PluginVersion {
        private String updatedAt;
    }
}
