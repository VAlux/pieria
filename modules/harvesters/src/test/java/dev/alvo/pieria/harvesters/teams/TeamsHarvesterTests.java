package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamsHarvesterTests {

  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  private final StubMicrosoft microsoft = new StubMicrosoft();

  @AfterEach
  void stop() {
    microsoft.close();
  }

  private record Outcome(int exit, List<HarvestRecord> records, String stderr) {

    HarvestRecord.Failure failure() {
      return (HarvestRecord.Failure) records.getLast();
    }

    JsonNode cursor() {
      JsonNode cursor = null;
      for (HarvestRecord record : records) {
        if (record instanceof HarvestRecord.Checkpoint checkpoint) {
          cursor = checkpoint.cursor();
        }
      }
      return cursor;
    }

    List<String> itemIds() {
      return records.stream().filter(HarvestRecord.Item.class::isInstance).map(r -> ((HarvestRecord.Item) r).id()).toList();
    }
  }

  private static Instant at(LocalDate day, int hour) {
    return day.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
  }

  private Outcome run(String command, ObjectNode config, String refreshToken, JsonNode cursor) {
    Map<String, String> secrets = new HashMap<>();
    if (refreshToken != null) {
      secrets.put("refreshToken", refreshToken);
    }
    HarvestRequest request = new HarvestRequest(1, config, secrets, cursor, null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = PieriaHarvest.run(new String[]{"teams", command},
      new ByteArrayInputStream((RequestCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8)), out,
      new PrintStream(err, true, StandardCharsets.UTF_8));
    List<HarvestRecord> records = new ArrayList<>();
    for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        records.add(RecordCodec.decode(line));
      }
    }
    return new Outcome(exit, records, err.toString(StandardCharsets.UTF_8));
  }

  private Outcome run(String command) {
    return run(command, microsoft.config(), "r1", null);
  }

  @Test
  void checkRotatesTheRefreshToken() {
    microsoft.refresh(StubMicrosoft.tokens("at1", "r2", StubMicrosoft.ALL_SCOPES));

    Outcome outcome = run("check");

    assertThat(outcome.exit()).as(outcome.stderr()).isZero();
    assertThat(outcome.records()).containsExactly(new HarvestRecord.SecretUpdate("refreshToken", "r2"));
  }

  @Test
  void checkWithoutSignInIsAuth() {
    Outcome outcome = run("check", microsoft.config(), null, null);

    assertThat(outcome.exit()).isEqualTo(1);
    assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(outcome.failure().message()).contains("pieria source authorize");
  }

  @Test
  void channelsAddedAfterAChatsOnlySignInIsAuth() {
    microsoft.refresh(StubMicrosoft.tokens("at1", "r1", "User.Read Chat.Read"));
    ObjectNode config = microsoft.config();
    config.putArray("channels").add("Eng");

    Outcome outcome = run("check", config, "r1", null);

    assertThat(outcome.exit()).isEqualTo(1);
    assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(outcome.failure().message()).contains("ChannelMessage.Read.All").contains("admin");
  }

  @Test
  void channelsWithoutConsentFailAuthNamingTheAdminConsent() {
    microsoft.refresh(StubMicrosoft.consentRequired());
    ObjectNode config = microsoft.config();
    config.putArray("channels").add("Eng");

    Outcome outcome = run("check", config, "r1", null);

    assertThat(outcome.exit()).isEqualTo(1);
    assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(outcome.failure().message()).contains("ChannelMessage.Read.All").contains("admin");
  }

  @Test
  void harvestEmitsTheRotatedTokenFirst() {
    microsoft.refresh(StubMicrosoft.tokens("at1", "r2", StubMicrosoft.ALL_SCOPES));
    microsoft.chat("c1", "group", "Release").message("1", "Ada", at(TODAY.minusDays(2), 9), "<p>ship it on friday</p>");

    Outcome outcome = run("harvest");

    assertThat(outcome.exit()).as(outcome.stderr()).isZero();
    assertThat(outcome.records().getFirst()).isEqualTo(new HarvestRecord.SecretUpdate("refreshToken", "r2"));
    assertThat(outcome.itemIds()).containsExactly("chat:c1:" + TODAY.minusDays(2));
  }

  @Test
  void deletedDayBecomesATombstone() {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Release");
    LocalDate day = TODAY.minusDays(2);
    ObjectNode first = chat.message("1", "Ada", at(day, 9), "<p>ship it on friday</p>");
    ObjectNode second = chat.message("2", "Bo", at(day, 10), "<p>agreed, friday it is</p>");
    Outcome run1 = run("harvest");
    assertThat(run1.itemIds()).containsExactly("chat:c1:" + day);

    String deletedAt = Instant.now().plusSeconds(1).toString();
    for (ObjectNode message : List.of(first, second)) {
      message.put("deletedDateTime", deletedAt).put("lastModifiedDateTime", deletedAt);
    }
    Outcome run2 = run("harvest", microsoft.config(), "r1", run1.cursor());

    assertThat(run2.exit()).as(run2.stderr()).isZero();
    assertThat(run2.records()).contains(new HarvestRecord.Tombstone("chat:c1:" + day));
    assertThat(run2.itemIds()).isEmpty();
  }

  @Test
  void deletedPostBecomesATombstone() {
    StubMicrosoft.Channel ops = microsoft.team("t1", "Eng").channel("c1", "Ops");
    ObjectNode post = ops.post("100", "Ada", at(TODAY.minusDays(1), 9), "<p>the api is down</p>");
    ObjectNode config = microsoft.config().put("includeChats", false);
    config.putArray("channels").add("Eng/Ops");
    Outcome run1 = run("harvest", config, "r1", null);
    assertThat(run1.itemIds()).containsExactly("channel:t1:c1:100");

    String deletedAt = Instant.now().plusSeconds(1).toString();
    post.put("deletedDateTime", deletedAt).put("lastModifiedDateTime", deletedAt);
    Outcome run2 = run("harvest", config, "r1", run1.cursor());

    assertThat(run2.records()).contains(new HarvestRecord.Tombstone("channel:t1:c1:100"));
  }

  @Test
  void harvestNeverSweeps() {
    microsoft.chat("c1", "group", "Release").message("1", "Ada", at(TODAY.minusDays(2), 9), "<p>ship it on friday</p>");

    Outcome outcome = run("harvest");

    assertThat(outcome.records()).noneMatch(record -> record instanceof HarvestRecord.Sweep || record instanceof HarvestRecord.Seen);
  }

  @Test
  void authorizeEmitsCodeThenSecret() {
    microsoft.deviceTokens(StubMicrosoft.oauthError("authorization_pending", "pending"),
      StubMicrosoft.tokens("at9", "r9", StubMicrosoft.ALL_SCOPES));

    Outcome outcome = run("authorize", microsoft.config(), null, null);

    assertThat(outcome.exit()).as(outcome.stderr()).isZero();
    assertThat(outcome.records()).hasSize(2);
    HarvestRecord.DeviceCode code = (HarvestRecord.DeviceCode) outcome.records().getFirst();
    assertThat(code.userCode()).isEqualTo("ABCD-EFGH");
    assertThat(code.verificationUri()).isEqualTo("https://microsoft.com/devicelogin");
    assertThat(code.expiresInSeconds()).isEqualTo(900);
    assertThat(code.message()).contains("ABCD-EFGH");
    assertThat(outcome.records().getLast()).isEqualTo(new HarvestRecord.SecretUpdate("refreshToken", "r9"));
    assertThat(String.join("\n", outcome.records().stream().map(RecordCodec::encode).toList())).doesNotContain(StubMicrosoft.DEVICE_CODE);
  }

  @Test
  void authorizeDeclinedIsAuth() {
    microsoft.deviceTokens(StubMicrosoft.oauthError("authorization_declined", "declined"));

    Outcome outcome = run("authorize", microsoft.config(), null, null);

    assertThat(outcome.exit()).isEqualTo(1);
    assertThat(outcome.records().getFirst()).isInstanceOf(HarvestRecord.DeviceCode.class);
    assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(outcome.failure().message()).isEqualTo("the sign-in was declined");
  }

  @Test
  void authorizeWithoutTheChannelGrantFailsBeforeSaving() {
    microsoft.deviceTokens(StubMicrosoft.tokens("at9", "r9", "User.Read Chat.Read"));
    ObjectNode config = microsoft.config();
    config.putArray("channels").add("Eng");

    Outcome outcome = run("authorize", config, null, null);

    assertThat(outcome.exit()).isEqualTo(1);
    assertThat(outcome.records()).noneMatch(HarvestRecord.SecretUpdate.class::isInstance);
    assertThat(outcome.failure().message()).contains("admin");
  }
}
