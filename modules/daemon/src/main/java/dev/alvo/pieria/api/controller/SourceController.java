package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.CreateSourceRequest;
import dev.alvo.pieria.api.request.RunSourceRequest;
import dev.alvo.pieria.api.request.SourceSecretsRequest;
import dev.alvo.pieria.api.request.UpdateSourceRequest;
import dev.alvo.pieria.api.response.SourceAuthorizationResponse;
import dev.alvo.pieria.api.response.SourceCheckResponse;
import dev.alvo.pieria.api.response.SourceListResponse;
import dev.alvo.pieria.api.response.SourceResponse;
import dev.alvo.pieria.api.response.SourceRunListResponse;
import dev.alvo.pieria.api.response.SourceRunResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.harvest.AuthorizationState;
import dev.alvo.pieria.harvest.HarvestRun;
import dev.alvo.pieria.harvest.HarvestSource;
import dev.alvo.pieria.harvest.HarvestSourceService;
import dev.alvo.pieria.harvest.SourceCheckResult;
import dev.alvo.pieria.harvest.SourceDraft;
import dev.alvo.pieria.harvest.SourceMode;
import dev.alvo.pieria.harvest.SourceUpdate;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/** Manages a profile's harvest sources. Responses name secrets but never contain their values. */
@RestController
@RequestMapping("/v1/profiles/{name}/sources")
public class SourceController {

  private static final long DEFAULT_INTERVAL_SECONDS = 21_600;
  private static final String REMOTE_PROVIDER_WARNING = "Text from this source will be sent to your configured model provider.";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HarvestSourceService service;

  public SourceController(HarvestSourceService service) {
    this.service = service;
  }

  private static String wire(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static SourceMode mode(String value) {
    return value == null ? null : SourceMode.valueOf(value.toUpperCase(Locale.ROOT));
  }

  private static JsonNode configOf(HarvestSource source) {
    try {
      return MAPPER.readTree(source.configJson());
    } catch (JacksonException e) {
      return MAPPER.createObjectNode();
    }
  }

  private static SourceRunResponse toRun(HarvestRun run) {
    return new SourceRunResponse(run.id(), run.dryRun(), wire(run.status()), run.startedAt(), run.finishedAt(), run.itemsSeen(),
      run.itemsChanged(), run.itemsDeleted(), run.memoriesStored(), run.chars(), run.errorClass(), run.errorMessage(), run.stderrTail());
  }

  private SourceResponse toResponse(String profile, HarvestSource source) {
    List<HarvestRun> runs = service.runs(profile, source.id(), 1);
    return new SourceResponse(source.id(), profile, source.harvesterId(), configOf(source), service.secretNames(source.id()).stream().sorted().toList(),
      wire(source.mode()), source.intervalSeconds(), source.maxItemsPerRun(), source.maxCharsPerRun(), wire(source.status()),
      source.nextRunAt(), source.consecutiveFailures(), source.sweepBlockedCount(), service.acceptNextSweepPending(source.id()),
      service.isRunning(source.id()), service.usesRemoteModelProvider() ? List.of(REMOTE_PROVIDER_WARNING) : List.of(),
      runs.isEmpty() ? null : toRun(runs.getFirst()));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SourceResponse create(@PathVariable String name, @Valid @RequestBody CreateSourceRequest request) {
    HarvestSource source = service.create(name, new SourceDraft(request.id(), request.harvester(), request.config(), request.secrets(),
      mode(request.mode()), request.intervalSeconds() == null ? DEFAULT_INTERVAL_SECONDS : request.intervalSeconds(),
      request.maxItemsPerRun(), request.maxCharsPerRun(), Boolean.TRUE.equals(request.start())));
    return toResponse(name, source);
  }

  @GetMapping
  public SourceListResponse list(@PathVariable String name) {
    return new SourceListResponse(service.list(name).stream().map(source -> toResponse(name, source)).toList());
  }

  @GetMapping("/{id}")
  public SourceResponse get(@PathVariable String name, @PathVariable String id) {
    return toResponse(name, service.get(name, id));
  }

  @PatchMapping("/{id}")
  public SourceResponse update(@PathVariable String name, @PathVariable String id, @RequestBody UpdateSourceRequest request) {
    return toResponse(name, service.update(name, id, new SourceUpdate(request.config(), mode(request.mode()), request.intervalSeconds(),
      request.maxItemsPerRun(), request.maxCharsPerRun(), Boolean.TRUE.equals(request.resetCursor()))));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable String name, @PathVariable String id,
                     @RequestParam(defaultValue = "false") boolean forgetMemories) {
    service.remove(name, id, forgetMemories);
  }

  @PostMapping("/{id}/check")
  public SourceCheckResponse check(@PathVariable String name, @PathVariable String id) {
    SourceCheckResult result = service.check(name, id);
    return new SourceCheckResponse(result.ok(), result.errorClass() == null ? null : result.errorClass().wire(), result.message());
  }

  @PostMapping("/{id}/authorize")
  public SourceAuthorizationResponse authorize(@PathVariable String name, @PathVariable String id) {
    return toAuthorization(service.authorize(name, id));
  }

  @GetMapping("/{id}/authorize")
  public SourceAuthorizationResponse authorization(@PathVariable String name, @PathVariable String id) {
    return service.authorization(name, id).map(SourceController::toAuthorization)
      .orElseThrow(() -> new NotFoundException("no sign-in is in progress for source " + id));
  }

  private static SourceAuthorizationResponse toAuthorization(AuthorizationState state) {
    return new SourceAuthorizationResponse(state.status().wire(), state.verificationUri(), state.userCode(), state.message(),
      state.expiresAt(), state.errorClass() == null ? null : state.errorClass().wire(), state.error());
  }

  @PostMapping("/{id}/run")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse run(@PathVariable String name, @PathVariable String id,
                                @RequestBody(required = false) RunSourceRequest request) {
    return new TaskSubmitResponse(service.run(name, id, request != null && Boolean.TRUE.equals(request.dryRun())).toString());
  }

  @GetMapping("/{id}/runs")
  public SourceRunListResponse runs(@PathVariable String name, @PathVariable String id,
                                    @RequestParam(defaultValue = "10") int limit) {
    return new SourceRunListResponse(service.runs(name, id, Math.clamp(limit, 1, 100)).stream().map(SourceController::toRun).toList());
  }

  @PostMapping("/{id}/pause")
  public SourceResponse pause(@PathVariable String name, @PathVariable String id) {
    service.pause(name, id);
    return get(name, id);
  }

  @PostMapping("/{id}/resume")
  public SourceResponse resume(@PathVariable String name, @PathVariable String id) {
    service.resume(name, id);
    return get(name, id);
  }

  @PostMapping("/{id}/accept-sweep")
  public SourceResponse acceptSweep(@PathVariable String name, @PathVariable String id) {
    service.acceptNextSweep(name, id);
    return get(name, id);
  }

  @PutMapping("/{id}/secrets")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void secrets(@PathVariable String name, @PathVariable String id, @Valid @RequestBody SourceSecretsRequest request) {
    service.setSecrets(name, id, request.secrets());
  }
}
