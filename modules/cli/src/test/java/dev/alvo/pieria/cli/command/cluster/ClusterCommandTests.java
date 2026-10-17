package dev.alvo.pieria.cli.command.cluster;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class ClusterCommandTests {
  @Test void attachCarriesExpectedRevisionAndTypedMember() {
    try(StubDaemon daemon=StubDaemon.start()) {
      daemon.stub("/clusters/group-1/members",200,"{}");
      int exit=new CommandLine(new PieriaCli()).execute("cluster","attach","group-1",
        "--member","memory:source-1","--revision","7","--profile","alpha",
        "--daemon-url",daemon.baseUrl());
      assertEquals(0,exit);
      var request=daemon.lastRequestTo("/clusters/group-1/members");
      assertNotNull(request);
      assertEquals("POST",request.method());
      assertTrue(request.body().contains("\"expectedRevision\":7"));
      assertTrue(request.body().contains("\"kind\":\"memory\""));
      assertTrue(request.body().contains("\"id\":\"source-1\""));
    }
  }

  @Test void exportAndAuditUseProfileScopedRoutes() {
    try(StubDaemon daemon=StubDaemon.start()) {
      daemon.stub("/clusters/export",200,"{}");
      daemon.stub("/clustering/runs/run-1/audit",200,"[]");
      assertEquals(0,new CommandLine(new PieriaCli()).execute("cluster","export",
        "--profile","alpha","--daemon-url",daemon.baseUrl()));
      assertEquals(0,new CommandLine(new PieriaCli()).execute("cluster","audit","run-1",
        "--profile","alpha","--daemon-url",daemon.baseUrl()));
      assertEquals("/v1/profiles/alpha/clusters/export",daemon.lastRequestTo("/clusters/export").path());
      assertEquals("/v1/profiles/alpha/clustering/runs/run-1/audit",
        daemon.lastRequestTo("/clustering/runs/run-1/audit").path());
    }
  }
}
