package server.controller;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import server.manager.NodeManager;
import server.service.GatewayService;
import server.service.ServerService;
import server.types.data.NodeUsage;
import server.types.data.PaginationRequest;
import server.types.data.ServerConfig;
import server.types.data.ServerMisMatch;
import server.types.request.SendCommandBody;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MindustryToolPlayerDto;
import dto.MapDto;
import dto.ModDto;
import dto.PlayerDto;
import dto.ServerStateDto;
import events.BaseEvent;
import events.ServerEvents.LogEvent;
import enums.NodeRemoveReason;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("api/v2")
@RequiredArgsConstructor
public class ServerController {

    private final NodeManager nodeManager;
    private final ServerService serverService;
    private final GatewayService gatewayService;

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<BaseEvent> getServers() {
        return serverService.getEvents();
    }

    @GetMapping("/servers/{id}/files")
    public Mono<ResponseEntity<Object>> getFiles(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.getFiles(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @GetMapping("/servers/{id}/files/exists")
    public Mono<Boolean> isFileExists(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.isFileExists(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @GetMapping("/servers/{id}/files/download")
    public Mono<ResponseEntity<Resource>> downloadFile(//
            @PathVariable("id") UUID id, @RequestParam(name = "path", required = true) String path) {

        return nodeManager.getFile(id, path)
                .map(file -> {
                    try {
                        InputStreamResource resource = new InputStreamResource(
                                new FileInputStream(file.file()));

                        return ResponseEntity.ok()//
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                        String.format("attachment; filename=%s", Paths.get(path).getFileName()))
                                .contentLength(file.length())//
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)//
                                .body(resource);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException("File not found");
                    }
                });
    }

    @PostMapping(value = "/servers/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<Void> writeFile(
            @PathVariable("id") UUID serverId, @RequestPart("path") String path,
            @RequestPart(value = "file", required = false) FilePart file) {

        return serverService.writeFile(serverId, path, file);
    }

    @PostMapping(value = "/servers/{id}/folders")
    Mono<Boolean> createFolder(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.createFolder(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @DeleteMapping("/servers/{id}/files")
    Mono<Boolean> deleteFile(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.deleteFile(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @GetMapping("/servers/{id}/commands")
    public Flux<ServerCommandDto> getCommand(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMapMany(client -> client.getServer().getCommands());
    }

    @PostMapping("/servers/{id}/commands")
    public Mono<Void> sendCommand(@PathVariable("id") UUID serverId, @RequestBody() SendCommandBody command) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().sendCommand(command.getCommand()));
    }

    @PostMapping(path = "/servers/{id}/host", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LogEvent> host(@Validated @RequestBody ServerConfig request) {
        return serverService.host(request);
    }

    @GetMapping("/servers/{id}/players")
    public Flux<PlayerDto> getPlayers(@PathVariable("id") UUID serverId) {
        return serverService.getPlayers(serverId);
    }

    @PostMapping("/servers/{id}/players/{playerId}")
    public Mono<Void> setPlayer(@PathVariable("id") UUID serverId, @PathVariable("playerId") String playerId,
            @RequestBody MindustryToolPlayerDto request) {
        return serverService.updatePlayer(serverId, request);
    }

    @GetMapping("/servers/{id}/state")
    public Mono<ServerStateDto> state(@PathVariable("id") UUID serverId) {
        return serverService.state(serverId);
    }

    @GetMapping(path = "/servers/{id}/usage", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<NodeUsage> getUsage(@PathVariable("id") UUID serverId) {
        return serverService.getUsage(serverId);
    }

    @DeleteMapping("/servers/{id}/remove")
    public Mono<Void> remove(@PathVariable("id") UUID serverId) {
        return serverService.remove(serverId, NodeRemoveReason.USER_REQUEST);
    }

    @PostMapping("/servers/{id}/pause")
    public Mono<Boolean> pause(@PathVariable("id") UUID serverId) {
        return serverService.pause(serverId);
    }

    @GetMapping(path = "/servers/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public Mono<byte[]> image(@PathVariable("id") UUID serverId) {
        return serverService.getImage(serverId);
    }

    @GetMapping("servers/{id}/mods")
    public Flux<ModDto> getMods(@PathVariable("id") UUID serverId) {
        return serverService.getMods(serverId);
    }

    @GetMapping("servers/{id}/maps")
    public Flux<MapDto> getMaps(@PathVariable("id") UUID serverId) {
        return serverService.getMaps(serverId);
    }

    @GetMapping("servers/{id}/kicks")
    public Mono<Map<String, Long>> getKicks(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getKickedIps());
    }

    @GetMapping("servers/{id}/player-infos")
    public Flux<PlayerInfoDto> getPlayerInfos(//
            @PathVariable("id") UUID serverId, //
            @Validated PaginationRequest request,
            @RequestParam(name = "banned", required = false) Boolean banned, //
            @RequestParam(name = "filter", required = false) String filter//
    ) {
        return gatewayService.of(serverId).flatMapMany(
                client -> client.getServer().getPlayers(request.getPage(), request.getSize(), banned, filter));
    }

    @GetMapping("servers/{id}/json")
    public Mono<JsonNode> getJson(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getJson());
    }

    @PostMapping("servers/{id}/mismatch")
    public Flux<ServerMisMatch> getMismatch(//
            @PathVariable("id") UUID serverId,
            @Validated @RequestBody ServerConfig config//
    ) {
        return serverService.getMismatch(serverId, config);
    }

    @GetMapping("mods")
    public Flux<ManagerModDto> getManagerMods() {
        return serverService.getManagerMods();
    }

    @GetMapping("maps")
    public Flux<ManagerMapDto> getManagerMaps() {
        return serverService.getManagerMaps();
    }

    @GetMapping(path = "/servers/{id}/workflow/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<JsonNode> getWorkflowEvents(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMapMany(client -> client.getServer().getWorkflowEvents());
    }

    @GetMapping("servers/{id}/workflow/nodes")
    public Mono<JsonNode> getWorkflowNodes(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getWorkflowNodes());
    }

    @PostMapping("servers/{id}/workflow/nodes/{nodeId}/emit")
    public Mono<JsonNode> getWorkflowNodes(@PathVariable("id") UUID serverId, @PathVariable("nodeId") String nodeId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getWorkflowNodes());
    }

    @GetMapping("servers/{id}/workflow/version")
    public Mono<Long> getWorkflowVersion(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getWorkflowVersion());
    }

    @GetMapping("servers/{id}/workflow")
    public Mono<JsonNode> getWorkflow(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().getWorkflow());
    }

    @PostMapping("servers/{id}/workflow")
    public Mono<Void> saveWorkflow(@PathVariable("id") UUID serverId, @Validated @RequestBody JsonNode payload) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().saveWorkflow(payload));
    }

    @PostMapping("servers/{id}/workflow/load")
    public Mono<JsonNode> loadWorkflow(@PathVariable("id") UUID serverId, @Validated @RequestBody JsonNode payload) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().loadWorkflow(payload));
    }

}
