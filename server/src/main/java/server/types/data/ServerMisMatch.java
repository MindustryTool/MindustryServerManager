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
	private final String field;
	private final String current;
	private final String expected;

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
			if (state.getMods().stream()
					.noneMatch(runningMod -> runningMod.getFilename().equals(mod.getFilename()))) {
				result.add(
						new ServerMisMatch(
								"Mod " + mod.getName() + ":" + mod.getFilename()
										+ " is not loaded",
								"N/A",
								mod.getFilename()));
			}
		}

		for (var runningMod : state.getMods()) {
			if (mods.stream()
					.noneMatch(mod -> mod.getFilename().equals(runningMod.getFilename()))) {
				result.add(
						new ServerMisMatch(
								"Mod " + runningMod.getName() + ":"
										+ runningMod.getFilename()
										+ " is not running",
								runningMod.getFilename(),
								"N/A"));
			}
		}

		if  (!Objects.equals(expectedConfig.getIsAutoTurnOff(), currentConfig.getIsAutoTurnOff())) {
			result.add(
					new ServerMisMatch(
							"Auto turn off mismatch",
							currentConfig.getIsAutoTurnOff() + "",
							expectedConfig.getIsAutoTurnOff() + ""));
		}

		if (!Objects.equals(expectedConfig.getMode(), currentConfig.getMode())) {
			result.add(
					new ServerMisMatch(
							"Mode mismatch",
							currentConfig.getMode(),
							expectedConfig.getMode()));
		}

		if (!Objects.equals(expectedConfig.getImage(), currentConfig.getImage())) {
			result.add(
					new ServerMisMatch(
							"Image mismatch",
							currentConfig.getImage(),
							expectedConfig.getImage()));
		}

		for (var entry : expectedConfig.getEnv().entrySet()) {
			if (!currentConfig.getEnv().containsKey(entry.getKey())) {
				result.add(
						new ServerMisMatch(
								"Env " + entry.getKey() + " is not set",
								"N/A",
								entry.getValue()));
			} else if (!currentConfig.getEnv().get(entry.getKey()).equals(entry.getValue())) {
				result.add(
						new ServerMisMatch(
								"Env " + entry.getKey() + " mismatch",
								currentConfig.getEnv().get(entry.getKey()),
								entry.getValue()));
			}
		}

		if (!Objects.equals(expectedConfig.getIsHub(), currentConfig.getIsHub())) {
			result.add(
					new ServerMisMatch(
							"Hub mismatch",
							currentConfig.getIsHub() + "",
							expectedConfig.getIsHub() + ""));
		}

		if (!Objects.equals(expectedConfig.getPort(), currentConfig.getPort())) {
			result.add(
					new ServerMisMatch(
							"Port mismatch",
							currentConfig.getPort() + "",
							expectedConfig.getPort() + ""));
		}

		if (!Objects.equals(expectedConfig.getHostCommand(), currentConfig.getHostCommand())) {
			result.add(
					new ServerMisMatch(
							"Host command mismatch",
							currentConfig.getHostCommand(),
							expectedConfig.getHostCommand()));
		}

		if (!Objects.equals(expectedConfig.getCpu(), currentConfig.getCpu())) {
			result.add(
					new ServerMisMatch(
							"Plan cpu mismatch",
							currentConfig.getCpu() + "",
							expectedConfig.getCpu() + ""));
		}

		if (!Objects.equals(expectedConfig.getMemory(), currentConfig.getMemory())) {
			result.add(
					new ServerMisMatch(
							"Plan ram mismatch",
							currentConfig.getMemory() + "",
							expectedConfig.getMemory() + ""));
		}

		return result;
	}
}
