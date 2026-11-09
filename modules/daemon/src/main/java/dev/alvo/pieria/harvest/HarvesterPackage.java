package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;

/** A harvester ready to be installed, with the checksums its pin will hold. */
public record HarvesterPackage(HarvesterManifest manifest, String entrypointSha256, String manifestSha256) {
}
