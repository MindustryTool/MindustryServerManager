package server.types.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.github.dockerjava.api.exception.BadRequestException;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import dto.ModDto;
import dto.ServerStateDto;
import dto.ServerStatus;

@Data
@Accessors(chain = true)
@RequiredArgsConstructor
public class ServerMisMatch {
	private String field;
	private String current;
	private String expected;

	public static List<ServerMisMatch> from(
			ServerMetadata meta,
			ServerConfig expectedConfig,
			ServerStateDto state,
			List<ModDto> mods//
	) {
		if (state.getStatus().equals(ServerStatus.NOT_RESPONSE)) {
			throw new BadRequestException("Server not response");
		}

		var currentConfig = meta.getConfig();

		List<ServerMisMatch> result = new ArrayList<>();

		for (var mod : mods) {
			if (state.getMods().stream().noneMatch(runningMod -> runningMod.getName().equals(mod.getName()))) {
				result.add(new ServerMisMatch()
						.setField("Mod " + mod.getName() + " is not loaded, path: " + mod.getFilename())
						.setCurrent("N/A")
						.setExpected(mod.getName()));
			}
		}

		for (var runningMod : state.getMods().stream().filter(mod -> !mod.getName().equals("PluginLoader")).toList()) {
			if (mods.stream().noneMatch(mod -> mod.getName().equals(runningMod.getName()))) {
				result.add(new ServerMisMatch()
						.setField("Mod " + runningMod.getName() + " is deleted, path: " + runningMod.getFilename())
						.setCurrent(runningMod.getName())
						.setExpected("Deleted"));
			}
		}

		if (!Objects.equals(expectedConfig.getIsAutoTurnOff(), currentConfig.getIsAutoTurnOff())) {
			result.add(new ServerMisMatch()
					.setField("Auto turn off mismatch")
					.setCurrent(currentConfig.getIsAutoTurnOff() + "")
					.setExpected(expectedConfig.getIsAutoTurnOff() + ""));
		}

		if (!Objects.equals(expectedConfig.getMode(), currentConfig.getMode())) {
			result.add(new ServerMisMatch()
					.setField("Mode mismatch")
					.setCurrent(currentConfig.getMode())
					.setExpected(expectedConfig.getMode()));
		}

		if (!Objects.equals(expectedConfig.getImage(), currentConfig.getImage())) {
			result.add(new ServerMisMatch()
					.setField("Image mismatch")
					.setCurrent(currentConfig.getImage())
					.setExpected(expectedConfig.getImage()));
		}

		for (var entry : expectedConfig.getEnv().entrySet()) {
			if (!currentConfig.getEnv().containsKey(entry.getKey())) {
				result.add(new ServerMisMatch()
						.setField("Env " + entry.getKey() + " is not set")
						.setCurrent("N/A")
						.setExpected(entry.getValue()));
			} else if (!currentConfig.getEnv().get(entry.getKey()).equals(entry.getValue())) {
				result.add(new ServerMisMatch()
						.setField("Env " + entry.getKey() + " mismatch")
						.setCurrent(currentConfig.getEnv().get(entry.getKey()))
						.setExpected(entry.getValue()));
			}
		}

		if (!Objects.equals(expectedConfig.getIsHub(), currentConfig.getIsHub())) {
			result.add(new ServerMisMatch()
					.setField("Hub mismatch")
					.setCurrent(currentConfig.getIsHub() + "")
					.setExpected(expectedConfig.getIsHub() + ""));
		}

		if (!Objects.equals(expectedConfig.getPort(), currentConfig.getPort())) {
			result.add(new ServerMisMatch()
					.setField("Port mismatch")
					.setCurrent(currentConfig.getPort() + "")
					.setExpected(expectedConfig.getPort() + ""));
		}

		if (!Objects.equals(expectedConfig.getHostCommand(), currentConfig.getHostCommand())) {
			result.add(new ServerMisMatch()
					.setField("Host command mismatch")
					.setCurrent(currentConfig.getHostCommand())
					.setExpected(expectedConfig.getHostCommand()));
		}

		if (!Objects.equals(expectedConfig.getCpu(), currentConfig.getCpu())) {
			result.add(new ServerMisMatch()
					.setField("Plan cpu mismatch")
					.setCurrent(currentConfig.getCpu() + "")
					.setExpected(expectedConfig.getCpu() + ""));
		}

		if (!Objects.equals(expectedConfig.getMemory(), currentConfig.getMemory())) {
			result.add(new ServerMisMatch()
					.setField("Plan ram mismatch")
					.setCurrent(currentConfig.getMemory() + "")
					.setExpected(expectedConfig.getMemory() + ""));
		}

		return result;
	}
}
