package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "revise", description = "Correct or invalidate an assertion with optimistic concurrency.", mixinStandardHelpOptions = true)
public final class ProfileReviseCommand extends AbstractProfileCommand {
  @Parameters(index = "0") String name;
  @Parameters(index = "1") String id;
  @Option(names = "--assertion", required = true) String assertion;
  @Option(names = "--expected-revision", required = true) String expectedRevision;
  @Option(names = "--content") String content;
  @Option(names = "--valid-from") String validFrom;
  @Option(names = "--valid-to") String validTo;
  @Option(names = "--invalidate-at") String invalidateAt;
  @Option(names = "--open-ended", description = "Remove the asserted end bound.") boolean openEnded;

  @Override protected int run(ProfileClient client) {
    log.info(client.toJson(client.revise(name, id, new RevisionRequest(assertion, expectedRevision, content, validFrom, validTo, invalidateAt, openEnded))));
    return 0;
  }
}
