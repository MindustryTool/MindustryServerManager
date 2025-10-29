package server.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;

import arc.util.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import server.config.Const;
import server.manager.NodeManager;
import server.types.data.ServerConfig;
import dto.MindustryToolPlayerDto;
import dto.PlayerDto;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
import dto.ServerStateDto;
import events.BaseEvent;
import enums.NodeRemoveReason;
import events.StopEvent;
import jakarta.annotation.PreDestroy;
import server.utils.ApiError;
import server.utils.Utils;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

	private final Const envConfig;
	private final EventBus eventBus;
	private final NodeManager nodeManager;
	private final ConcurrentHashMap<UUID, Mono<GatewayClient>> cache = new ConcurrentHashMap<>();

	public Mono<GatewayClient> of(UUID serverId) {
		return cache.computeIfAbsent(serverId,
				_id -> Mono.<GatewayClient>create(
						(emittor) -> new GatewayClient(serverId, envConfig, emittor::success, emittor::error))
						.cache());
	}

	@PreDestroy
	private void cancelAll() {
		cache.values()
				.forEach(mono -> mono
						.doOnError(ApiError.class, error -> Log.err(error.getMessage()))
						.onErrorComplete(ApiError.class)
						.block(Duration.ofSeconds(25)).cancel());
	}

	@RequiredArgsConstructor
	public class GatewayClient {

		@Getter
		private final UUID id;

		@Getter
		private final Server server;

		private boolean connected = false;

		private final Instant createdAt = Instant.now();

		private final Disposable eventJob;

		public GatewayClient(UUID id, Const envConfig, Consumer<GatewayClient> onConnect, Consumer<Throwable> onError) {
			this.id = id;
			this.server = new Server();

			this.eventJob = this.server.getEvents()
					.flatMap(event -> {
						var name = event.get("name").asText(null);

						if (connected == false) {
							connected = true;
							onConnect.accept(this);
						}

						if (name == null) {
							Log.warn("Invalid event: " + event.asText());

							return Mono.empty();
						}

						var eventType = BaseEvent.getEventMap().get(name);

						if (eventType == null) {
							Log.warn("Invalid event name: " + name + " in " + BaseEvent.getEventMap().keySet());

							return Mono.empty();
						}

						// Parse JsonNode into clazz
						BaseEvent data = (BaseEvent) Utils.readJsonAsClass(event, eventType);

						eventBus.fire(data);

						return Mono.empty();
					})
					.onErrorMap(TimeoutException.class,
							error -> new ApiError(HttpStatus.BAD_REQUEST, "Fetch events timeout"))
					.retryWhen(Retry.fixedDelay(20, Duration.ofSeconds(10)))
					.onErrorMap(Exceptions::isRetryExhausted,
							error -> new ApiError(HttpStatus.BAD_REQUEST,
									"Fetch events timeout: " + error.getMessage()))
					.doOnError(error -> Log.err(error.getMessage()))
					.doOnError((error) -> onError.accept(error))
					.onErrorComplete(ApiError.class)
					.doFinally(_ignore -> remove())
					.subscribeOn(Schedulers.boundedElastic())
					.subscribe();

			Log.info("Create GatewayClient for server: " + id);
		}

		public void cancel() {
			eventJob.dispose();
		}

		private void remove() {
			cache.remove(id);
			Log.info("Close GatewayClient for server: " + id + " running for "
					+ Utils.toReadableString(Duration.between(createdAt, Instant.now())));

			nodeManager.remove(id, NodeRemoveReason.FETCH_EVENT_TIMEOUT)
					.doOnError(ApiError.class, error -> Log.err(error.getMessage()))
					.onErrorComplete(ApiError.class)
					.subscribe();

			eventBus.fire(new StopEvent(id, NodeRemoveReason.FETCH_EVENT_TIMEOUT));
		}

		public class Server {
			private final WebClient webClient = WebClient.builder()
					.codecs(configurer -> configurer
							.defaultCodecs()
							.maxInMemorySize(16 * 1024 * 1024))
					.baseUrl(URI.create(
							Const.IS_DEVELOPMENT//
									? "http://localhost:9999/" //
									: "http://" + id.toString() + ":9999/")
							.toString())
					.defaultHeaders(headers -> {
						headers.set("X-SERVER-ID", id.toString());
						headers.set("X-CREATED-AT", createdAt.toString());
					})
					.defaultStatusHandler(Utils::handleStatus, Utils::createError)
					.build();

			public Mono<JsonNode> getJson() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)//
						.uri("json")//
						.retrieve()//
						.bodyToMono(JsonNode.class), Duration.ofSeconds(5), "Get json");
			}

			public Mono<String> getPluginVersion() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)//
						.uri("plugin-version")//
						.retrieve()//
						.bodyToMono(String.class), Duration.ofSeconds(5), "Get plugin version");
			}

			public Mono<Void> updatePlayer(MindustryToolPlayerDto request) {
				return Utils.wrapError(webClient.method(HttpMethod.PUT)//
						.uri("players/" + request.getUuid())//
						.bodyValue(request)//
						.retrieve()//
						.bodyToMono(String.class), Duration.ofSeconds(5), "Set player")
						.then();
			}

			public Mono<Boolean> pause() {
				return Utils.wrapError(webClient.method(HttpMethod.POST)
						.uri("pause")
						.retrieve()
						.bodyToMono(Boolean.class), Duration.ofSeconds(5), "Pause");
			}

			public Flux<PlayerDto> getPlayers() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("players")
								.retrieve()
								.bodyToFlux(PlayerDto.class),
						Duration.ofSeconds(5), "Get players");
			}

			public Mono<ServerStateDto> getState() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("state")
								.retrieve()
								.bodyToMono(ServerStateDto.class),
						Duration.ofMillis(1000),
						"Get state");
			}

			public Mono<byte[]> getImage() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("image")
								.accept(MediaType.IMAGE_PNG)
								.retrieve()
								.bodyToMono(byte[].class),
						Duration.ofSeconds(5),
						"Get image");
			}

			public Mono<Void> sendCommand(String... command) {
				return Utils.wrapError(
						webClient.method(HttpMethod.POST)
								.uri("commands")
								.bodyValue(command)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(2),
						"Send command");
			}

			public Mono<Void> say(String message) {
				return Utils.wrapError(
						webClient.method(HttpMethod.POST)
								.uri("say")
								.bodyValue(message)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(2),
						"Say message");
			}

			public Mono<Void> host(ServerConfig request) {
				return Utils.wrapError(
						webClient.method(HttpMethod.POST)
								.uri("host")
								.bodyValue(request)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(15),
						"Host server");
			}

			public Mono<Boolean> isHosting() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)
						.uri("hosting")
						.retrieve()
						.bodyToMono(Boolean.class), Duration.ofMillis(100), "Check hosting");
			}

			public Flux<ServerCommandDto> getCommands() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)
						.uri("commands")
						.retrieve()
						.bodyToFlux(ServerCommandDto.class), Duration.ofSeconds(10), "Get commands");
			}

			public Flux<PlayerInfoDto> getPlayers(int page, int size, Boolean banned, String filter) {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri(builder -> builder.path("players-info")
										.queryParam("page", page)
										.queryParam("size", size)
										.queryParam("banned", banned)
										.queryParam("filter", filter)
										.build())
								.retrieve()
								.bodyToFlux(PlayerInfoDto.class),
						Duration.ofSeconds(10),
						"Get player infos");
			}

			public Mono<Map<String, Long>> getKickedIps() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("kicked-ips")
								.retrieve()
								.bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {
								}),
						Duration.ofSeconds(10),
						"Get kicked IPs");
			}

			public Mono<JsonNode> getRoutes() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("routes")
								.retrieve()
								.bodyToMono(JsonNode.class),
						Duration.ofSeconds(10),
						"Get routes");
			}

			public Mono<JsonNode> getWorkflowNodes() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("workflow/nodes")
								.retrieve()
								.bodyToMono(JsonNode.class),
						Duration.ofSeconds(10),
						"Get workflow nodes");
			}

			public Flux<JsonNode> getWorkflowEvents() {
				return Utils.wrapError(
						webClient.method(HttpMethod.GET)
								.uri("workflow/events")
								.accept(MediaType.TEXT_EVENT_STREAM)
								.retrieve()
								.bodyToFlux(JsonNode.class),
						Duration.ofSeconds(10),
						"Get workflow events").log();
			}

			public Mono<JsonNode> emitWorkflowNode(String nodeId) {
				return Utils.wrapError(webClient.method(HttpMethod.GET)
						.uri("workflow/emit/" + nodeId)
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Emit workflow node");
			}

			public Mono<Long> getWorkflowVersion() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)
						.uri("/workflow/version")
						.retrieve()
						.bodyToMono(Long.class), Duration.ofSeconds(10), " Get workflow version");
			}

			public Mono<JsonNode> getWorkflow() {
				return Utils.wrapError(webClient.method(HttpMethod.GET)
						.uri("/workflow")
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Get workflow");
			}

			public Mono<Void> saveWorkflow(JsonNode payload) {
				return Utils.wrapError(webClient.method(HttpMethod.POST)
						.uri("/workflow")
						.bodyValue(payload)
						.retrieve()
						.bodyToMono(Void.class), Duration.ofSeconds(10), "Save workflow");
			}

			public Mono<JsonNode> loadWorkflow(JsonNode payload) {
				return Utils.wrapError(webClient.method(HttpMethod.POST)
						.uri("/workflow/load")
						.bodyValue(payload)
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Load workflow");
			}

			public Flux<JsonNode> getEvents() {
				return Utils.wrapError(webClient.get()
						.uri("events")
						.accept(MediaType.TEXT_EVENT_STREAM)
						.retrieve()
						.bodyToFlux(JsonNode.class), Duration.ofDays(365), "Get events");
			}
		}
	}
}
