package dev.alvo.pieria.api.response;

import java.util.List;

/** What a package would install, shown before the user agrees to it. */
public record HarvesterPackageResponse(HarvesterResponse harvester, List<String> command, String entrypoint,
                                       String entrypointSha256, String manifestSha256) {
}
