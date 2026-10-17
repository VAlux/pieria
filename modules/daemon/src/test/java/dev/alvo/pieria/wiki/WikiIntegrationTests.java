package dev.alvo.pieria.wiki;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.controller.WikiController;
import dev.alvo.pieria.cluster.ClusterRepository;
import dev.alvo.pieria.api.request.WikiEvaluateRequest;
import dev.alvo.pieria.config.*;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.alvo.pieria.wiki.WikiData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WikiIntegrationTests {
  @TempDir Path temp;
  HikariDataSource ds;JdbcClient jdbc;MemoryStore store;WikiProperties cfg;WikiProjectionService projection;
  WikiService service;MockMvc mvc;String profile;AtomicInteger calls;
  static final String ARTICLE="{\"sections\":[{\"title\":\"Overview\",\"paragraphs\":[{\"text\":\"The sources describe a local daemon.\",\"citations\":[\"E1\",\"E2\"]}]}]}";
  @BeforeEach void setup() {
    ds=new HikariDataSource();ds.setJdbcUrl("jdbc:sqlite:"+temp.resolve("wiki.db")+"?busy_timeout=10000&foreign_keys=on&transaction_mode=IMMEDIATE");
    ds.setMaximumPoolSize(4);ds.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(ds).load().migrate();jdbc=JdbcClient.create(ds);
    var target=new SqliteMemoryStore(jdbc);var proxy=new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
    store=(MemoryStore)proxy.getProxy();
    cfg=new WikiProperties(true,200,5000,100,180,2,40,8000,1500,.8,.75,.35,.25,.20,80,16000,.80,.90,.60,10);
    var props=new PieriaProperties(null,null,null,new PieriaProperties.Model("small","large","embedding",3,1,null,null),null,null,null);
    var resolver=new EffectiveConfigResolver(props,store,cfg);calls=new AtomicInteger();
    ModelGateway model=new ModelGateway() {
      @Override public String synthesizeRecall(String query,java.util.List<dev.alvo.pieria.retrieval.model.RecallCandidate> candidates) { throw new AssertionError("Recall must not run"); }
      @Override public float[] embed(String text) {calls.incrementAndGet();throw new IllegalStateException("offline");}
      @Override public String synthesizeWiki(Bundle b) {calls.incrementAndGet();return ARTICLE;}
    };
    service=new WikiService(store,resolver,model,props);projection=new WikiProjectionService(store);
    profile=store.getOrCreateProfile("wiki-test").id();mvc=MockMvcBuilders.standaloneSetup(new WikiController(service)).build();
  }
  @AfterEach void close() {ds.close();}
  Memory source(String text,String key) {return store.insertMemory(profile,Memory.of(MemoryType.EVENT,text,"wiki-fixture",key,null));}
  void project() {for(int i=0;i<500;i++) if(!projection.tick(profile,cfg))return;fail("Projection did not settle");}
  Page topic(String key) {return store.readWiki(w->w.anchor(profile,"topic:"+key).orElseThrow());}
  WikiEvaluateRequest request(Page page,String intent) {return new WikiEvaluateRequest(page.revision(),page.currentVersion(),intent);}
  Page seed() {source("The local daemon owns the SQLite writer.","storage");source("The memory database uses WAL transactions.","storage");project();return topic("storage");}
  Bundle bundle(Page page) {return store.readWiki(w->WikiEvidenceSelector.select(w,profile,page,cfg,service.rendering(profile)));}
  Job enqueue(Page page) {var evaluation=service.evaluate("wiki-test",page.id(),request(page,"initial"));return store.readWiki(w->w.job(profile,evaluation.job().id()).orElseThrow());}
  Job claim() {return store.writeWiki(w->w.claim(profile,Instant.now(),2).orElseThrow());}
  String publish(Page page) {
    enqueue(page);Job job=claim();Bundle bundle=bundle(page);
    assertTrue(store.<Boolean>writeWiki(w->w.publish(job,bundle,WikiEvidenceSelector.validate(ARTICLE,bundle))));
    return store.readWiki(w->w.page(profile,page.id()).orElseThrow().currentVersion());
  }
  @Test void descriptorAndEvidenceSearchNeedNoModelAndTasksRemainDiscoverable() {
    var page=seed();source("Prepare the release checklist for packaging.",null);
    store.insertMemory(profile,Memory.of(MemoryType.TASK,"Review daemon certificates","t",null,null));project();
    assertTrue(service.search("wiki-test","SQLite","lexical",20).pages().stream().anyMatch(p->p.id().equals(page.id())));
    assertEquals(1,service.search("wiki-test","certificates","lexical",20).unassigned().size());
    assertEquals("absent",service.page("wiki-test",page.id()).page().generation());assertEquals(0,calls.get());
  }
  @Test void sharedClusterHasOneStableWikiPageWithBothNavigationPaths() {
    var a=source("SQLite permits one writer.","storage");
    var b=source("A durable queue records pending jobs.","jobs");
    var c=source("A worker resumes pending jobs.","jobs");
    var sourceMembers=List.of(a,b,c).stream().map(m->new ClusterRepository.Member("memory",m.id())).toList();
    var shared=store.writeClusters(r->r.create(profile,"Job execution","jobs",sourceMembers,"test"));
    var first=store.writeClusters(r->r.create(profile,"Storage architecture","storage",
      List.of(new ClusterRepository.Member("cluster",shared.id())),"test"));
    var second=store.writeClusters(r->r.create(profile,"Worker reliability","reliability",
      List.of(new ClusterRepository.Member("cluster",shared.id())),"test"));
    project();
    var page=store.readWiki(w->w.anchor(profile,"cluster:"+shared.id()).orElseThrow());
    assertEquals(3,service.page("wiki-test",page.id()).totalSources());
    assertEquals(2,store.<Long>readWiki(w->w.links(profile,page.id()).stream()
      .filter(link->link.kind().equals("cluster-parent")).count()).longValue());
    assertTrue(Set.of(store.readWiki(w->w.anchor(profile,"cluster:"+first.id()).orElseThrow().id()),
      store.readWiki(w->w.anchor(profile,"cluster:"+second.id()).orElseThrow().id())).contains(page.parentId()));
    var before=page.id();project();
    assertEquals(before,store.readWiki(w->w.anchor(profile,"cluster:"+shared.id()).orElseThrow().id()));
    assertNotEquals(page.id(),topic("storage").id());
  }
  @Test void clusterWikiUsesEligibleClaimsAndOriginalCitations() {
    var a=source("SQLite permits one writer.","storage");
    var b=source("The queue records jobs durably.","storage");
    var c=source("Workers resume jobs after restart.","storage");
    var members=List.of(a,b,c).stream().map(m->new ClusterRepository.Member("memory",m.id())).toList();
    var cluster=store.writeClusters(r->r.create(profile,"Reliable storage","storage",members,"test"));
    var supports=List.of(a,b,c).stream().map(m->new ClusterRepository.Source(m.id(),
      jdbc.sql("SELECT id FROM assertion_revisions WHERE memory_id=?").param(m.id()).query(String.class).single())).toList();
    String fingerprint=store.readClusters(r->r.fingerprint(profile,cluster.id()));
    store.writeClusters(r->r.publish(profile,cluster.id(),1,fingerprint,"The system stores and resumes jobs.",
      List.of(new ClusterRepository.Claim("The system stores and resumes jobs.",supports,supports)),
      "fake-large","v1","{}","test"));
    project();
    var page=store.readWiki(w->w.anchor(profile,"cluster:"+cluster.id()).orElseThrow());
    var bundle=bundle(page);
    assertEquals(1,bundle.clusterClaims().size());
    assertEquals(3,bundle.clusterClaims().getFirst().citations().size());
    assertTrue(bundle.evidence().stream().noneMatch(e->e.type().equals("cluster")));
    store.forgetMemory(profile,a.id());
    project();
    var updated=store.readWiki(w->w.anchor(profile,"cluster:"+cluster.id()).orElseThrow());
    assertTrue(bundle(updated).clusterClaims().isEmpty());
  }
  @Test void wikiReadDoesNotReserveTheSqliteWriter() throws Exception {
    Page page=seed();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Page> reader=executor.submit(()->store.readWiki(w->{
        Page current=w.page(profile,page.id()).orElseThrow();entered.countDown();
        try {if(!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("reader timed out");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        return current;
      }));
      assertTrue(entered.await(3,TimeUnit.SECONDS));
      Future<?> writer=executor.submit(()->store.writeWiki(w->{w.checked(profile,page.id(),1,Instant.now());return null;}));
      try {writer.get(2,TimeUnit.SECONDS);} finally {release.countDown();}
      assertEquals(page.id(),reader.get(3,TimeUnit.SECONDS).id());
    }
  }
  @Test void projectionPersistsOneSourcePerWriterReservation() {
    for(int i=0;i<20;i++) source("A distinct storage fixture " + i,"bulk");
    projection.tick(profile,cfg);
    assertEquals(1,store.<Integer>readWiki(w->w.members(profile,w.anchor(profile,"topic:bulk").orElseThrow().id()).size()).intValue());
    project();
    assertEquals(20,service.page("wiki-test",topic("bulk").id()).totalSources());
  }
  @Test void sourceCursorUsesProfileAndIdIndex() {
    var plan=jdbc.sql("EXPLAIN QUERY PLAN SELECT id FROM memories m WHERE profile_id=? AND id>? "
        +"AND forgotten=0 ORDER BY id LIMIT ?")
      .params(profile,"a",1).query((rs,_)->rs.getString("detail")).list();
    assertTrue(plan.stream().anyMatch(step->step.contains("idx_mem_profile_id")),plan.toString());
  }
  @Test void workerAdvancesSeveralShortBatchesPerCycle() {
    for(int i=0;i<20;i++) source("A distinct worker fixture " + i,"worker");
    var worker=new WikiWorker(store,projection,service,new ModelGateway() {
      @Override public String synthesizeRecall(String query,java.util.List<dev.alvo.pieria.retrieval.model.RecallCandidate> candidates) {throw new AssertionError();}
      @Override public float[] embed(String text) {throw new AssertionError();}
      @Override public String synthesizeWiki(Bundle bundle) {throw new AssertionError();}
    });
    try {
      worker.tick();
      assertFalse(store.<String>readWiki(w->w.state(profile).cursor()).isEmpty());
      project();
      assertEquals(20,service.page("wiki-test",topic("worker").id()).totalSources());
    } finally {worker.close();}
  }
  @Test void directoryPageKeepsItsInlineEvidenceBounded() {
    for(int i=0;i<50;i++) source("Source number " + i,"group:item-"+i);
    project();
    Page group=store.readWiki(w->w.anchor(profile,"group:topic:group").orElseThrow());
    var response=service.page("wiki-test",group.id());
    assertEquals(50,response.totalSources());
    assertEquals(40,response.evidence().size());
    assertEquals(10,response.omittedSources());
  }
  @Test void unchangedPageReusesVersionAndUnrelatedInsertDoesNotStaleIt() {
    Page page=seed();String version=publish(page);
    source("The garden irrigation valve opens at dawn.","garden");
    assertEquals("needs-check",service.page("wiki-test",page.id()).page().freshness());project();
    var response=service.page("wiki-test",page.id());assertEquals("current",response.page().freshness());assertEquals(version,response.versionId());
    Page latest=topic("storage");assertEquals("ready",service.evaluate("wiki-test",page.id(),request(latest,"refresh")).status());
    assertEquals(0,calls.get());
  }
  @Test void uncitedNewEvidenceStalesWithoutAutomaticGeneration() {
    Page page=seed();publish(page);source("SQLite backups require a consistent snapshot.","storage");project();
    var response=service.page("wiki-test",page.id());assertEquals("stale",response.page().freshness());assertNotNull(response.body());assertEquals(0,calls.get());
  }
  @Test void concurrentReadersShareOneDurableJob() throws Exception {
    Page page=seed();
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var futures=new ArrayList<Future<String>>();for(int i=0;i<8;i++) futures.add(executor.submit(()->service.evaluate("wiki-test",page.id(),request(page,"initial")).job().id()));
      Set<String> jobs=new HashSet<>();for(var f:futures)jobs.add(f.get(10,TimeUnit.SECONDS));assertEquals(1,jobs.size());
    }
    assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM wiki_jobs").query(Integer.class).single());
  }
  @Test void forgettingImmediatelyPurgesCachedVersionsAndFencesInferenceBeforeWorkerRuns() {
    Page page=seed();publish(page);
    String id=store.readWiki(w->w.members(profile,page.id()).getFirst().memoryId());
    store.forgetMemory(profile,id);
    assertEquals(410,assertThrows(WikiException.class,()->service.page("wiki-test",page.id())).status());
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM wiki_versions WHERE body<>''").query(Integer.class).single());
    assertFalse(service.search("wiki-test","storage","lexical",20).pages().stream().anyMatch(p->p.id().equals(page.id())));
    project();assertNull(service.page("wiki-test",page.id()).body());
  }
  @Test void forgottenSourceCannotBePublishedByInFlightJob() {
    Page page=seed();enqueue(page);Job job=claim();Bundle bundle=bundle(page);
    store.forgetMemory(profile,bundle.evidence().getFirst().memoryId());
    assertFalse(store.<Boolean>writeWiki(w->w.publish(job,bundle,"old article")));
    assertEquals("cancelled",service.job("wiki-test",job.id()).status());
  }
  @Test void changedInputsCannotPublishEvenWhenCallerSuppliesAnOldBundle() {
    Page page=seed();enqueue(page);Job job=claim();Bundle old=bundle(page);
    source("Additional storage evidence arrived during synthesis.","storage");
    assertFalse(store.<Boolean>writeWiki(w->w.publish(job,old,"stale completion")));
    project();assertFalse(store.<Boolean>writeWiki(w->w.publish(job,old,"stale completion")));
    assertNull(service.page("wiki-test",page.id()).body());
  }
  @Test void deletionCannotBeResurrectedByJobAndProfileIsolationHolds() {
    Page page=seed();enqueue(page);Job job=claim();Bundle bundle=bundle(page);
    store.getOrCreateProfile("other");
    assertEquals(404,assertThrows(WikiException.class,()->service.page("other",page.id())).status());
    assertEquals(404,assertThrows(WikiException.class,()->service.job("other",job.id())).status());
    store.deleteProfile(profile);assertFalse(store.<Boolean>writeWiki(w->w.publish(job,bundle,"old")));
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM wiki_jobs").query(Integer.class).single());
  }
  @Test void expiredLeaseRecoveryRejectsOldFence() {
    Page page=seed();enqueue(page);Job first=claim();Bundle bundle=bundle(page);
    Job second=store.writeWiki(w->w.claim(profile,Instant.now().plusSeconds(60),2).orElseThrow());
    assertTrue(second.fence()>first.fence());assertFalse(store.<Boolean>writeWiki(w->w.publish(first,bundle,"old")));
    assertTrue(store.<Boolean>writeWiki(w->w.publish(second,bundle,WikiEvidenceSelector.validate(ARTICLE,bundle))));
  }
  @Test void projectionCanResumeBetweenBatches() {
    seed();source("A new storage recipe records restore checks.","storage");
    projection.tick(profile,cfg);assertFalse(store.readWiki(w->w.state(profile)).initialized());
    projection=new WikiProjectionService(store);project();assertTrue(store.readWiki(w->w.state(profile)).initialized());
  }
  @Test void controllerGetsNeverGenerateAndRejectHistoricalCutoffs() throws Exception {
    Page page=seed();
    mvc.perform(get("/v1/profiles/wiki-test/wiki/pages/"+page.id())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    mvc.perform(get("/v1/profiles/wiki-test/wiki?asOf=2020-01-01T00:00:00Z")).andExpect(status().isBadRequest());
    mvc.perform(post("/v1/profiles/wiki-test/wiki/pages/"+page.id()+"/evaluate").contentType("application/json")
      .content("{\"expectedDescriptorRevision\":"+page.revision()+",\"intent\":\"initial\"}"))
      .andExpect(status().isAccepted()).andExpect(jsonPath("$.job.status").value("queued"));assertEquals(0,calls.get());
  }
  @Test void invalidCitationsAndExternalLinksCannotBecomeArticles() {
    Bundle b=bundle(seed());
    assertThrows(WikiException.class,()->WikiEvidenceSelector.validate(ARTICLE.replace("E2","E999"),b));
    assertThrows(WikiException.class,()->WikiEvidenceSelector.validate(ARTICLE.replace("The sources describe a local daemon.","[Click](https://example.org)"),b));
    assertThrows(WikiException.class,()->WikiEvidenceSelector.validate(ARTICLE.replace("The sources describe a local daemon.","<script>alert(1)</script>"),b));
    assertTrue(WikiEvidenceSelector.validate(ARTICLE,b).contains("#source-E1"));
  }
  @Test void lexicalFallbackWhenEmbeddingProviderIsOffline() {
    seed();var result=service.search("wiki-test","SQLite","semantic",20);assertTrue(result.degraded());assertFalse(result.pages().isEmpty());assertEquals(1,calls.get());
  }
  @Test void mergeConfirmationNeedsDifferentCompletedProjectionRounds() {
    Page first=seed();source("A separate topic", "other");project();Page second=topic("other");
    store.writeWiki(w->{
      assertFalse(w.confirmMerge(profile,first.id(),second.id(),"scope", "round-1"));
      assertFalse(w.confirmMerge(profile,first.id(),second.id(),"scope", "round-1"));
      w.finishMergeRound(profile,"round-1");
      assertTrue(w.confirmMerge(profile,first.id(),second.id(),"scope", "round-2"));
      assertFalse(w.confirmMerge(profile,first.id(),second.id(),"changed", "round-2"));
      w.finishMergeRound(profile,"round-3");assertFalse(w.pendingMerges(profile));return null;
    });
  }
  @Test void overlappingSiblingTopicsMergeOnlyAfterASecondSweepAndKeepRedirects() {
    var graph=new dev.alvo.pieria.domain.graph.GraphFragment(
      List.of(dev.alvo.pieria.domain.graph.Entity.of("service","daemon","{}"),dev.alvo.pieria.domain.graph.Entity.of("database","SQLite","{}")),
      List.of(new dev.alvo.pieria.domain.graph.GraphFragment.EdgeTriple("daemon","service","owns","SQLite","database")));
    for(int i=0;i<4;i++) {
      Memory memory=source("The local SQLite daemon owns the database writer and uses write ahead logging for consistent durable transactions number "+i,
        i<2?"storage:local":"storage:runtime");
      store.attachGraph(profile,memory.id(),graph);
    }
    projection.tick(profile,cfg);projection.tick(profile,cfg);projection.tick(profile,cfg);
    Page left=topic("storage:local"),right=topic("storage:runtime");
    assertNotEquals(left.id(),right.id());
    assertEquals(left.id(),store.readWiki(w->w.canonical(profile,left.id())));
    assertEquals(right.id(),store.readWiki(w->w.canonical(profile,right.id())));
    project();
    assertEquals(store.<String>readWiki(w->w.canonical(profile,left.id())),store.<String>readWiki(w->w.canonical(profile,right.id())));
    assertEquals(0,calls.get());
  }
  @Test void splitChildrenKeepIdentityAfterTheirSeedIsForgotten() {
    cfg=new WikiProperties(true,200,5000,100,180,2,40,8000,1500,.8,.75,.35,.25,.20,5,16000,.80,.90,.60,10);
    for(int i=0;i<4;i++) {
      source("Postgres replication streams immutable WAL segments to replica nodes for durable backup number "+i,"large");
      source("Garden tomato seedlings require warm sunlight and daily watering during spring season number "+i,"large");
    }
    project();Page parent=topic("large");
    List<Page> children=store.readWiki(w->w.splitChildren(profile,parent.id()));assertEquals(2,children.size());
    String forgotten=store.readWiki(w->w.members(profile,children.getFirst().id()).getFirst().memoryId());
    store.forgetMemory(profile,forgotten);project();
    List<Page> later=store.readWiki(w->w.splitChildren(profile,parent.id()));
    assertEquals(children.stream().map(Page::id).toList(),later.stream().map(Page::id).toList());
    assertTrue(later.stream().allMatch(p->p.lifecycle().equals("active")));
    assertEquals(0,calls.get());
  }
  @Test void evidenceBudgetIncludesSerializedProvenanceAndPrompt() {
    for(int i=0;i<50;i++) source("Database operational notes "+i+" "+"source evidence ".repeat(100),"many");
    project();Bundle selected=bundle(topic("many"));
    assertTrue(selected.evidence().size()>=2);assertTrue(selected.omittedSources()>0);
    assertTrue(dev.alvo.pieria.tools.Tokens.estimate(WikiEvidenceSelector.prompt(selected))<=cfg.inputTokens());
  }
  @Test void evidenceCursorCannotBeReusedOnAnotherPage() {
    Page page=seed();source("Another topic source","other");project();
    String cursor=service.evidence("wiki-test",page.id(),null,1).nextCursor();assertNotNull(cursor);
    assertEquals(409,assertThrows(WikiException.class,()->service.evidence("wiki-test",topic("other").id(),cursor,1)).status());
  }
  @Test void timeBoundaryStalesCachedArticleWithoutAnotherIngest() throws Exception {
    Instant expiry=Instant.now().plusSeconds(3);
    store.insertMemory(profile,Memory.of(MemoryType.EVENT,"Temporary storage maintenance is active.","s","storage",null)
      .withValidity(Instant.now().minusSeconds(60),expiry,"temporary-maintenance"));
    source("The daemon owns durable storage.","storage");project();Page page=topic("storage");publish(page);
    long watermark=store.readWiki(w->w.watermark(profile));
    Thread.sleep(Math.max(0,java.time.Duration.between(Instant.now(),expiry).toMillis())+20);
    assertEquals(watermark,store.<Long>readWiki(w->w.watermark(profile)).longValue());
    assertEquals("needs-check",service.page("wiki-test",page.id()).page().freshness());
    project();assertEquals("stale",service.page("wiki-test",page.id()).page().freshness());assertEquals(0,calls.get());
  }
  @Test void migrationUpgradesExistingV1DatabaseAndProjectsItsMemories() {
    var legacy=new HikariDataSource();legacy.setJdbcUrl("jdbc:sqlite:"+temp.resolve("legacy.db"));
    try(legacy) {
      Flyway.configure().dataSource(legacy).target("1").load().migrate();
      var oldStore=new SqliteMemoryStore(JdbcClient.create(legacy));String p=oldStore.getOrCreateProfile("legacy").id();
      Memory memory=oldStore.insertMemory(p,Memory.of(MemoryType.EVENT,"Existing memory survives upgrade.","s","legacy",null));
      Flyway.configure().dataSource(legacy).load().migrate();
      var projection=new WikiProjectionService(oldStore);for(int i=0;i<10 && projection.tick(p,cfg);i++) {}
      assertEquals(memory.content(),oldStore.findMemoryById(memory.id()).orElseThrow().content());
      assertEquals(1,oldStore.<Integer>readWiki(w->w.members(p,w.anchor(p,"topic:legacy").orElseThrow().id()).size()).intValue());
    }
  }
}
