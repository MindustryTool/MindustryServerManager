package server.service;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.fasterxml.jackson.databind.JsonNode;

import arc.util.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import server.config.Const;
import server.types.data.ServerConfig;
import dto.MindustryToolPlayerDto;
import dto.PlayerDto;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
import dto.ServerStateDto;
import events.BaseEvent;
import server.utils.ApiError;
import server.utils.Utils;
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
	private final ConcurrentHashMap<UUID, Mono<GatewayClient>> cache = new ConcurrentHashMap<>();

	public Mono<GatewayClient> of(UUID serverId) {
		return cache.computeIfAbsent(serverId,
				_id -> Mono.<GatewayClient>create((emittor) -> new GatewayClient(serverId, envConfig, emittor::success))
						.cache()
						.timeout(Duration.ofMinutes(1)));
	}

	@RequiredArgsConstructor
	public class GatewayClient {

		@Getter
		private final UUID id;

		@Getter
		private final Server server;

		private boolean connected = false;

		public GatewayClient(UUID id, Const envConfig, Consumer<GatewayClient> onConnect) {
			this.id = id;

			this.server = new Server();

			this.server.getEvents()
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
					.retryWhen(Retry.fixedDelay(24, Duration.ofSeconds(10)))
					.doOnError((error) -> Log.err(error.getMessage()))
					.doFinally(_ignore -> {
						cache.remove(id);
						Log.info("Close GatewayClient for server: " + id);
					})
					.subscribeOn(Schedulers.boundedElastic())
					.subscribe();

			Log.info("Create GatewayClient for server: " + id);
		}

		private static boolean handleStatus(HttpStatusCode status) {
			return switch (HttpStatus.valueOf(status.value())) {
				case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_ENTITY, CONFLICT -> true;
				default -> false;
			};
		}

		private static Mono<Throwable> createError(ClientResponse response) {
			return response.bodyToMono(JsonNode.class)
					.map(message -> new ApiError(HttpStatus.valueOf(response.statusCode().value()),
							message.has("message") //
									? message.get("message").asText()
									: message.toString()));
		}

		public class Server {

			public boolean isConnectionException(Throwable e) {
				if (e == null) {
					return false;
				}

				return e instanceof ConnectException || isConnectionException(e.getCause());
			}

			private <T> Mono<T> wrapError(Mono<T> publisher, Duration timeout, String message) {
				return publisher
						.onErrorMap(WebClientRequestException.class, error -> {
							if (error.getCause() instanceof UnknownHostException) {
								return new ApiError(HttpStatus.NOT_FOUND, "Server not found: " + error.getMessage());
							}

							if (isConnectionException(error.getCause())) {
								return new ApiError(HttpStatus.BAD_REQUEST,
										"Can not connect to server: " + error.getMessage());
							}

							return error;
						})
						.timeout(timeout)
						.onErrorMap(TimeoutException.class,
								error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout error: " + message));
			}

			private <T> Flux<T> wrapError(Flux<T> publisher, Duration timeout, String message) {
				return publisher
						.onErrorMap(WebClientRequestException.class, error -> {
							if (error.getCause() instanceof UnknownHostException) {
								return new ApiError(HttpStatus.NOT_FOUND, "Server not found");
							}

							return error;
						})
						.timeout(timeout)
						.onErrorMap(TimeoutException.class,
								error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout error: " + message));
			}

			private final WebClient webClient = WebClient.builder()
					.codecs(configurer -> configurer
							.defaultCodecs()
							.maxInMemorySize(16 * 1024 * 1024))
					.baseUrl(URI.create(
							Const.IS_DEVELOPMENT//
									? "http://localhost:9999/" //
									: "http://" + id.toString() + ":9999/")
							.toString())
					.defaultStatusHandler(GatewayClient::handleStatus, GatewayClient::createError)
					.build();

			public Mono<JsonNode> getJson() {
				return wrapError(webClient.method(HttpMethod.GET)//
						.uri("json")//
						.retrieve()//
						.bodyToMono(JsonNode.class), Duration.ofSeconds(5), "Get json");
			}

			public Mono<String> getPluginVersion() {
				return wrapError(webClient.method(HttpMethod.GET)//
						.uri("plugin-version")//
						.retrieve()//
						.bodyToMono(String.class), Duration.ofSeconds(5), "Get plugin version");
			}

			public Mono<Void> updatePlayer(MindustryToolPlayerDto request) {
				return wrapError(webClient.method(HttpMethod.PUT)//
						.uri("players/" + request.getUuid())//
						.bodyValue(request)//
						.retrieve()//
						.bodyToMono(String.class), Duration.ofSeconds(5), "Set player")
						.then();
			}

			public Mono<Boolean> pause() {
				return wrapError(webClient.method(HttpMethod.POST)
						.uri("pause")
						.retrieve()
						.bodyToMono(Boolean.class), Duration.ofSeconds(5), "Pause");
			}

			public Flux<PlayerDto> getPlayers() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("players")
								.retrieve()
								.bodyToFlux(PlayerDto.class),
						Duration.ofSeconds(5), "Get players");
			}

			public Mono<Void> ok() {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("ok")
						.retrieve()
						.bodyToMono(Void.class)
						.retryWhen(Retry.fixedDelay(400, Duration.ofMillis(100))), Duration.ofSeconds(40), "Check ok");
			}

			public Mono<ServerStateDto> getState() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("state")
								.retrieve()
								.bodyToMono(ServerStateDto.class),
						Duration.ofMillis(1000),
						"Get state");
			}

			public Mono<byte[]> getImage() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("image")
								.accept(MediaType.IMAGE_PNG)
								.retrieve()
								.bodyToMono(byte[].class),
						Duration.ofSeconds(5),
						"Get image");
			}

			public Mono<Void> sendCommand(String... command) {
				return wrapError(
						webClient.method(HttpMethod.POST)
								.uri("commands")
								.bodyValue(command)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(2),
						"Send command");
			}

			public Mono<Void> say(String message) {
				return wrapError(
						webClient.method(HttpMethod.POST)
								.uri("say")
								.bodyValue(message)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(2),
						"Say message");
			}

			public Mono<Void> host(ServerConfig request) {
				return wrapError(
						webClient.method(HttpMethod.POST)
								.uri("host")
								.bodyValue(request)
								.retrieve()
								.bodyToMono(Void.class),
						Duration.ofSeconds(15),
						"Host server");
			}

			public Mono<Boolean> isHosting() {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("hosting")
						.retrieve()
						.bodyToMono(Boolean.class)
						.retryWhen(Retry.fixedDelay(10 * 4, Duration.ofMillis(100)))
						.onErrorReturn(false), Duration.ofMillis(100), "Check hosting");
			}

			public Flux<ServerCommandDto> getCommands() {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("commands")
						.retrieve()
						.bodyToFlux(ServerCommandDto.class), Duration.ofSeconds(10), "Get commands");
			}

			public Flux<PlayerInfoDto> getPlayers(int page, int size, Boolean banned, String filter) {
				return wrapError(
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
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("kicked-ips")
								.retrieve()
								.bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {
								}),
						Duration.ofSeconds(10),
						"Get kicked IPs");
			}

			public Mono<JsonNode> getRoutes() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("routes")
								.retrieve()
								.bodyToMono(JsonNode.class),
						Duration.ofSeconds(10),
						"Get routes");
			}

			public Mono<JsonNode> getWorkflowNodes() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("workflow/nodes")
								.retrieve()
								.bodyToMono(JsonNode.class),
						Duration.ofSeconds(10),
						"Get workflow nodes");
			}

			public Flux<JsonNode> getWorkflowEvents() {
				return wrapError(
						webClient.method(HttpMethod.GET)
								.uri("workflow/events")
								.accept(MediaType.TEXT_EVENT_STREAM)
								.retrieve()
								.bodyToFlux(JsonNode.class),
						Duration.ofSeconds(10),
						"Get workflow events").log();
			}

			public Mono<JsonNode> emitWorkflowNode(String nodeId) {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("workflow/emit/" + nodeId)
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Emit workflow node");
			}

			public Mono<Long> getWorkflowVersion() {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("/workflow/version")
						.retrieve()
						.bodyToMono(Long.class), Duration.ofSeconds(10), " Get workflow version");
			}

			public Mono<JsonNode> getWorkflow() {
				return wrapError(webClient.method(HttpMethod.GET)
						.uri("/workflow")
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Get workflow");
			}

			public Mono<Void> saveWorkflow(JsonNode payload) {
				return wrapError(webClient.method(HttpMethod.POST)
						.uri("/workflow")
						.bodyValue(payload)
						.retrieve()
						.bodyToMono(Void.class), Duration.ofSeconds(10), "Save workflow");
			}

			public Mono<JsonNode> loadWorkflow(JsonNode payload) {
				return wrapError(webClient.method(HttpMethod.POST)
						.uri("/workflow/load")
						.bodyValue(payload)
						.retrieve()
						.bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Load workflow");
			}

			public Flux<JsonNode> getEvents() {
				return wrapError(webClient.get()
						.uri("events")
						.accept(MediaType.TEXT_EVENT_STREAM)
						.retrieve()
						.bodyToFlux(JsonNode.class), Duration.ofDays(365), "Get events");
			}
		}
	}
}
