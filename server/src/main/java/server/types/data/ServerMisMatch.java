package server.types.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.dockerjava.api.exception.BadRequestException;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import common.content.Mod;
import common.server.ServerConfig;
import common.server.ServerMetadata;
import common.server.ServerSnapshot;
import common.server.ServerStatus;

@Data
@Accessors(chain = true)
@RequiredArgsConstructor
public class ServerMisMatch {
	private String field;
	private String current;
	private String expected;

	@JsonIgnore
	private MisMatchType type;

	public static List<ServerMisMatch> from(
			ServerMetadata meta,
			ServerConfig expectedConfig,
			ServerSnapshot state,
			List<Mod> mods//
	) {
		return from(meta, expectedConfig, state, mods, null, null);
	}

	public static List<ServerMisMatch> from(
			ServerMetadata meta,
			ServerConfig expectedConfig,
			ServerSnapshot state,
			List<Mod> mods,
			String expectedJarHash,
			String currentJarHash//
	) {
		if (state.getStatus().equals(ServerStatus.NOT_RESPONSE)) {
			throw new BadRequestException("Server not response");
		}

		var currentConfig = meta.getConfig();

		List<ServerMisMatch> result = new ArrayList<>();

		for (var mod : mods) {
			if (state.getMods().stream().noneMatch(runningMod -> runningMod.getName().equals(mod.getName()))) {
				result.add(new ServerMisMatch()
						.setType(MisMatchType.MOD_MISSING)
						.setField("Mod " + mod.getName() + " is not loaded, path: " + mod.getFilename())
						.setCurrent("N/A")
						.setExpected(mod.getName()));
			}
		}

		for (var runningMod : state.getMods().stream().filter(mod -> !mod.getName().equals("PluginLoader")).toList()) {
			if (mods.stream().noneMatch(mod -> mod.getName().equals(runningMod.getName()))) {
				result.add(new ServerMisMatch()
						.setType(MisMatchType.MOD_DELETED)
						.setField("Mod " + runningMod.getName() + " is deleted, path: " + runningMod.getFilename())
						.setCurrent(runningMod.getName())
						.setExpected("Deleted"));
			}
		}

		if (!Objects.equals(expectedConfig.getIsAutoTurnOff(), currentConfig.getIsAutoTurnOff())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.AUTO_TURN_OFF)
					.setField("Auto turn off mismatch")
					.setCurrent(currentConfig.getIsAutoTurnOff() + "")
					.setExpected(expectedConfig.getIsAutoTurnOff() + ""));
		}

		if (!Objects.equals(expectedConfig.getMode(), currentConfig.getMode())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.MODE)
					.setField("Mode mismatch")
					.setCurrent(currentConfig.getMode())
					.setExpected(expectedConfig.getMode()));
		}

		if (!Objects.equals(expectedConfig.getImage(), currentConfig.getImage())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.IMAGE)
					.setField("Image mismatch")
					.setCurrent(currentConfig.getImage())
					.setExpected(expectedConfig.getImage()));
		}

		Map<String, String> expectedEnv = expectedConfig.getEnv() == null
				? Collections.emptyMap()
				: expectedConfig.getEnv();
		Map<String, String> currentEnv = currentConfig.getEnv() == null
				? Collections.emptyMap()
				: currentConfig.getEnv();
		for (var entry : expectedEnv.entrySet()) {
			if (!currentEnv.containsKey(entry.getKey())) {
				result.add(new ServerMisMatch()
						.setType(MisMatchType.ENV)
						.setField("Env " + entry.getKey() + " is not set")
						.setCurrent("N/A")
						.setExpected(entry.getValue()));
			} else if (!Objects.equals(currentEnv.get(entry.getKey()), entry.getValue())) {
				result.add(new ServerMisMatch()
						.setType(MisMatchType.ENV)
						.setField("Env " + entry.getKey() + " mismatch")
						.setCurrent(currentEnv.get(entry.getKey()))
						.setExpected(entry.getValue()));
			}
		}

		if (!Objects.equals(expectedConfig.getIsHub(), currentConfig.getIsHub())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.HUB)
					.setField("Hub mismatch")
					.setCurrent(currentConfig.getIsHub() + "")
					.setExpected(expectedConfig.getIsHub() + ""));
		}

		if (!Objects.equals(expectedConfig.getPort(), currentConfig.getPort())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.PORT)
					.setField("Port mismatch")
					.setCurrent(currentConfig.getPort() + "")
					.setExpected(expectedConfig.getPort() + ""));
		}

		if (!Objects.equals(expectedConfig.getHostCommand(), currentConfig.getHostCommand())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.HOST_COMMAND)
					.setField("Host command mismatch")
					.setCurrent(currentConfig.getHostCommand())
					.setExpected(expectedConfig.getHostCommand()));
		}

		if (!Objects.equals(expectedConfig.getName(), currentConfig.getName())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.NAME)
					.setField("Name mismatch")
					.setCurrent(currentConfig.getName())
					.setExpected(expectedConfig.getName()));
		}

		if (!Objects.equals(expectedConfig.getDescription(), currentConfig.getDescription())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.DESCRIPTION)
					.setField("Description mismatch")
					.setCurrent(currentConfig.getDescription())
					.setExpected(expectedConfig.getDescription()));
		}

		if (!Objects.equals(expectedConfig.getCpu(), currentConfig.getCpu())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.CPU)
					.setField("Plan cpu mismatch")
					.setCurrent(currentConfig.getCpu() + "")
					.setExpected(expectedConfig.getCpu() + ""));
		}

		if (!Objects.equals(expectedConfig.getMemory(), currentConfig.getMemory())) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.MEMORY)
					.setField("Plan ram mismatch")
					.setCurrent(currentConfig.getMemory() + "")
					.setExpected(expectedConfig.getMemory() + ""));
		}

		if (expectedJarHash != null && currentJarHash != null
				&& !Objects.equals(expectedJarHash, currentJarHash)) {
			result.add(new ServerMisMatch()
					.setType(MisMatchType.PLUGIN_JAR)
					.setField("Plugin jar mismatch")
					.setCurrent(currentJarHash)
					.setExpected(expectedJarHash));
		}

		return result;
	}
}
