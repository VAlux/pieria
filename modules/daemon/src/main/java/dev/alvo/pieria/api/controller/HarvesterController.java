package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.HarvesterPathRequest;
import dev.alvo.pieria.api.request.InstallHarvesterRequest;
import dev.alvo.pieria.api.response.HarvesterDoctorResponse;
import dev.alvo.pieria.api.response.HarvesterListResponse;
import dev.alvo.pieria.api.response.HarvesterPackageResponse;
import dev.alvo.pieria.api.response.HarvesterResponse;
import dev.alvo.pieria.harvest.HarvesterInstaller;
import dev.alvo.pieria.harvest.HarvesterPackage;
import dev.alvo.pieria.harvest.HarvesterRegistry;
import dev.alvo.pieria.harvest.RegisteredHarvester;
import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.manifest.ConfigFieldSpec;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/** Installs, lists and removes harvesters. */
@RestController
@RequestMapping("/v1/harvesters")
public class HarvesterController {

  private final HarvesterRegistry registry;
  private final HarvesterInstaller installer;

  public HarvesterController(HarvesterRegistry registry, HarvesterInstaller installer) {
    this.registry = registry;
    this.installer = installer;
  }

  private static HarvesterResponse toResponse(HarvesterManifest manifest) {
    return new HarvesterResponse(manifest.id(), manifest.name(), manifest.version(),
      manifest.capabilities().stream().map(Capability::wire).sorted().toList(), manifest.network(),
      manifest.config().stream().map(HarvesterController::toField).toList());
  }

  private static HarvesterResponse.Field toField(ConfigFieldSpec field) {
    return new HarvesterResponse.Field(field.key(), field.kind().wire(), field.label(), field.hint(), field.required(),
      field.options(), field.defaultValue());
  }

  @GetMapping
  public HarvesterListResponse list() {
    return new HarvesterListResponse(registry.list().stream().map(RegisteredHarvester::manifest).map(HarvesterController::toResponse).toList());
  }

  @GetMapping("/doctor")
  public HarvesterDoctorResponse doctor() {
    return new HarvesterDoctorResponse(registry.diagnose().stream()
      .map(entry -> new HarvesterDoctorResponse.Entry(entry.directory(), entry.ok(), entry.detail()))
      .toList());
  }

  @PostMapping("/inspect")
  public HarvesterPackageResponse inspect(@Valid @RequestBody HarvesterPathRequest request) {
    HarvesterPackage inspected = installer.inspect(Path.of(request.path()));
    HarvesterManifest manifest = inspected.manifest();
    return new HarvesterPackageResponse(toResponse(manifest), List.copyOf(manifest.command()), manifest.entrypoint(),
      inspected.entrypointSha256(), inspected.manifestSha256());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public HarvesterResponse install(@Valid @RequestBody InstallHarvesterRequest request) {
    return toResponse(installer.install(Path.of(request.path()), request.entrypointSha256(), request.manifestSha256(), Boolean.TRUE.equals(request.replace())).manifest());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable String id) {
    installer.remove(id);
  }
}
