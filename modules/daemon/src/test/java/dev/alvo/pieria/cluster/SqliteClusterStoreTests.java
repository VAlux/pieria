package dev.alvo.pieria.cluster;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.api.controller.ClusterController;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.alvo.pieria.cluster.ClusterRepository.Member;
import static org.junit.jupiter.api.Assertions.*;

class SqliteClusterStoreTests {
  @TempDir Path temp;
  HikariDataSource ds;
  JdbcClient jdbc;
  MemoryStore store;
  String profile;

  @BeforeEach void setup() {
    ds=new HikariDataSource();
    ds.setJdbcUrl("jdbc:sqlite:"+temp.resolve("clusters.db")+"?busy_timeout=10000&foreign_keys=on&transaction_mode=IMMEDIATE");
    ds.setMaximumPoolSize(4);
    ds.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc=JdbcClient.create(ds);
    var proxy=new ProxyFactory(new SqliteMemoryStore(jdbc));
    proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
    store=(MemoryStore)proxy.getProxy();
    profile=store.getOrCreateProfile("graph").id();
  }

  @AfterEach void close() { ds.close(); }

  Member memory(String content) {
    return new Member("memory",store.insertMemory(profile,Memory.of(MemoryType.EVENT,content,"fixture",null,null)).id());
  }
  Member memory(String content,String key) {
    return new Member("memory",store.insertMemory(profile,Memory.of(MemoryType.EVENT,content,"fixture",key,null)).id());
  }
  ClusterRepository.Cluster create(String title,Member... members) {
    return store.writeClusters(c->c.create(profile,title,title+" scope",List.of(members),"test"));
  }
  Member cluster(ClusterRepository.Cluster c) { return new Member("cluster",c.id()); }
  String publish(ClusterRepository.Cluster group,List<Member> originals,String text) {
    var sources=originals.stream().map(member->new ClusterRepository.Source(member.id(),
      jdbc.sql("SELECT id FROM assertion_revisions WHERE memory_id=?").param(member.id()).query(String.class).single())).toList();
    String fingerprint=store.readClusters(repo->repo.fingerprint(profile,group.id()));
    store.writeClusters(repo->repo.publish(profile,group.id(),group.revision(),fingerprint,text,
      List.of(new ClusterRepository.Claim(text,sources,sources)),"fake-large","v1","{}","test"));
    return jdbc.sql("SELECT memory_id FROM cluster_syntheses WHERE cluster_id=? ORDER BY recorded_sequence DESC LIMIT 1")
      .param(group.id()).query(String.class).single();
  }

  @Test void sharedChildrenAndDiamondsDeduplicateOriginals() {
    Member one=memory("SQLite has one writer");
    Member two=memory("WAL permits readers during writes");
    Member three=memory("Jobs use a durable queue");
    var storage=create("Storage",one,two);
    var jobs=create("Jobs",one,three);
    var root=create("Architecture",cluster(storage),cluster(jobs));
    var detail=store.readClusters(c->c.detail(profile,root.id(),null).orElseThrow());
    assertEquals(3,detail.cluster().distinctOriginalCount());
    assertEquals(List.of(root.id()),store.readClusters(c->c.detail(profile,storage.id(),null).orElseThrow().parents()));
  }
  @Test void relevantParentNeedsMatchingClaimOrSeparateMatchingBranches() {
    Member a=memory("storage writer");Member b=memory("storage readers");Member c=memory("storage WAL");
    Member d=memory("job queue");Member e=memory("job retry");Member f=memory("job lease");
    var storage=create("Storage",a,b,c);var jobs=create("Jobs",d,e,f);
    publish(storage,List.of(a,b,c),"Storage coordinates SQLite access.");
    publish(jobs,List.of(d,e,f),"Jobs use a durable queue.");
    var architecture=create("Architecture",cluster(storage),cluster(jobs));
    String parentMemory=publish(architecture,List.of(a,b,c,d,e,f),"The system has storage and job components.");
    var snapshot=store.temporalSnapshot(null,null);
    assertTrue(store.<List<String>>readClusters(repo->((SqliteClusterStore)repo)
      .relevantParents(profile,List.of(a.id()),"writer",snapshot,10)).isEmpty());
    assertEquals(List.of(parentMemory),store.<List<String>>readClusters(repo->((SqliteClusterStore)repo)
      .relevantParents(profile,List.of(a.id(),d.id()),"writer",snapshot,10)));
  }
  @Test void shortDistinctiveQueryUsesOnlyMatchingClaimCoverage() {
    Member wal=memory("WAL keeps readers available");
    Member writer=memory("One writer serializes transactions");
    Member queue=memory("Queued jobs resume later");
    var cluster=create("Storage",wal,writer,queue);
    var sources=List.of(wal,writer,queue).stream().map(member->new ClusterRepository.Source(member.id(),
      jdbc.sql("SELECT id FROM assertion_revisions WHERE memory_id=?")
        .param(member.id()).query(String.class).single())).toList();
    var claims=List.of(
      new ClusterRepository.Claim("WAL keeps readers available",List.of(sources.get(0)),List.of(sources.get(0))),
      new ClusterRepository.Claim("Transactions and queued work use one writer",
        List.of(sources.get(1),sources.get(2)),List.of(sources.get(1),sources.get(2))));
    String fingerprint=store.readClusters(repo->repo.fingerprint(profile,cluster.id()));
    store.writeClusters(repo->repo.publish(profile,cluster.id(),cluster.revision(),fingerprint,
      "WAL keeps readers available. Transactions and queued work use one writer.",
      claims,"fake-large","v1","{}","test"));
    String generated=jdbc.sql("SELECT memory_id FROM cluster_syntheses WHERE cluster_id=?")
      .param(cluster.id()).query(String.class).single();
    var snapshot=store.temporalSnapshot(null,null);
    assertEquals(Set.of(wal.id()),store.<Set<String>>readClusters(repo->((SqliteClusterStore)repo)
      .coveredOriginals(profile,generated,"WAL",snapshot)));
    assertEquals(Set.of(),store.<Set<String>>readClusters(repo->((SqliteClusterStore)repo)
      .coveredOriginals(profile,generated,"the",snapshot)));
  }
  @Test void publishingChildRefreshesEveryParentEvenWithoutSourceChange() {
    Member a=memory("shared detail");Member b=memory("child detail b");Member c=memory("child detail c");
    Member d=memory("first parent detail");Member e=memory("second parent detail");
    var child=create("Shared child",a,b,c);
    publish(child,List.of(a,b,c),"The shared child covers three details.");
    var first=create("First parent",cluster(child),d);
    var second=create("Second parent",cluster(child),e);
    publish(first,List.of(a,b,c,d),"The first parent includes the child and another detail.");
    publish(second,List.of(a,b,c,e),"The second parent includes the child and another detail.");
    jdbc.sql("DELETE FROM cluster_dirty WHERE profile_id=?").param(profile).update();

    publish(child,List.of(a,b,c),"A refreshed shared child covers three details.");

    var dirty=jdbc.sql("SELECT cluster_id FROM cluster_dirty WHERE profile_id=? ORDER BY cluster_id")
      .param(profile).query(String.class).list();
    assertEquals(java.util.Set.of(first.id(),second.id()),java.util.Set.copyOf(dirty));
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,first.id(),null).isEmpty()));
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,second.id(),null).isEmpty()));
  }
  @Test void dissolvingSharedChildDetachesOnlyItsParentEdges() {
    Member a=memory("shared original");Member b=memory("child b");Member c=memory("child c");
    Member d=memory("parent one detail");Member e=memory("parent two detail");
    var child=create("Shared child",a,b,c);
    var sibling=create("Sibling",a,d,e);
    var first=create("First",cluster(child),d);
    var second=create("Second",cluster(child),e);

    store.writeClusters(repo->repo.dissolve(profile,child.id(),child.revision(),"test"));

    assertEquals(List.of(d),store.readClusters(repo->repo.detail(profile,first.id(),null).orElseThrow().members()));
    assertEquals(List.of(e),store.readClusters(repo->repo.detail(profile,second.id(),null).orElseThrow().members()));
    assertEquals(java.util.Set.of(a,b,c),java.util.Set.copyOf(
      store.<List<Member>>readClusters(repo->repo.detail(profile,child.id(),null).orElseThrow().members())));
    assertTrue(store.<List<Member>>readClusters(repo->repo.detail(profile,sibling.id(),null).orElseThrow().members()).contains(a));
    assertTrue(store.<List<String>>readClusters(repo->repo.detail(profile,child.id(),null).orElseThrow().parents()).isEmpty());
    assertEquals(2,store.<Integer>readClusters(repo->repo.detail(profile,first.id(),null)
      .orElseThrow().cluster().revision()).intValue());
  }
  @Test void restorationRejectsSourceThatIsNoLongerValid() {
    Member a=memory("first");Member b=memory("second");Member c=memory("third");
    var group=create("Restorable",a,b,c);
    store.writeClusters(repo->repo.dissolve(profile,group.id(),1,"test"));
    jdbc.sql("UPDATE validity_segments SET valid_from=? WHERE revision_id IN "
      +"(SELECT id FROM assertion_revisions WHERE memory_id=?)")
      .params(dev.alvo.pieria.domain.temporal.TemporalSnapshot.timestamp(Instant.now().plusSeconds(3600)),
        a.id()).update();
    assertThrows(ClusterConflictException.class,()->store.writeClusters(repo->repo.restore(
      profile,group.id(),2,1,"test")));
    assertEquals("dissolved",store.readClusters(repo->repo.detail(profile,group.id(),null)
      .orElseThrow().cluster().state()));
  }
  @Test void restoreQueuesRegenerationAndReturnsDurableOperation() {
    Member a=memory("A restored subject detail");
    Member b=memory("A second restored subject detail");
    Member c=memory("A third restored subject detail");
    var cluster=create("Restored subject",a,b,c);
    store.writeClusters(repo->repo.dissolve(profile,cluster.id(),1,"manual"));
    var response=new ClusterController(store).restore("graph",cluster.id(),
      new ClusterController.Restore(2,1));
    assertEquals(202,response.getStatusCode().value());
    var restored=response.getBody();
    assertNotNull(restored);
    assertEquals(3,restored.cluster().revision());
    assertEquals("queued",restored.operation().state());
    assertTrue(response.getHeaders().getLocation().toString().endsWith(restored.operation().id()));
    assertTrue(store.<List<ClusterRepository.Work>>readClusters(repo->repo.work(profile,
      restored.operation().id(),10)).stream().anyMatch(work->work.subjectId().equals(cluster.id())));
  }
  @Test void regenerationContinuesBottomUpThroughNewlyDirtyAncestors() {
    Member a=memory("first");Member b=memory("second");Member c=memory("third");
    Member d=memory("fourth");
    var child=create("Child",a,b,c);
    publish(child,List.of(a,b,c),"Child summary.");
    var parent=create("Parent",cluster(child),d);
    String oldParent=publish(parent,List.of(a,b,c,d),"Parent summary.");
    jdbc.sql("DELETE FROM cluster_dirty WHERE profile_id=?").param(profile).update();
    var run=store.writeClusters(repo->{
      var started=repo.startRun(profile,"regenerate");
      repo.enqueueRegeneration(started.id(),child.id(),child.revision());
      return started;
    });

    new ClusterWorker(store,new ClusterGenerationService(store,clusterModel(()->{}))).tick();

    assertEquals("completed",store.readClusters(repo->repo.run(profile,run.id()).orElseThrow().state()));
    String newParent=jdbc.sql("SELECT memory_id FROM cluster_syntheses WHERE id=?")
      .param(store.readClusters(repo->repo.synthesis(profile,parent.id(),null).orElseThrow().id()))
      .query(String.class).single();
    assertNotEquals(oldParent,newParent);
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM cluster_dirty WHERE profile_id=?")
      .param(profile).query(Integer.class).single());
  }
  @Test void runDiscoveryUsesItsPersistedTemporalSnapshot() {
    memory("snapshot first","snapshot:topic");
    memory("snapshot second","snapshot:topic");
    Member third=memory("snapshot third","snapshot:topic");
    var run=store.writeClusters(repo->repo.startRun(profile,"manual"));
    Member later=memory("snapshot later","snapshot:topic");

    var topic=store.readClusters(repo->repo.topics(profile,"",10,run.snapshot())).getFirst();
    assertEquals(3,topic.members().size());
    assertFalse(topic.members().contains(later));
    assertTrue(topic.members().contains(third));
    assertFalse(store.<List<String>>readClusters(repo->repo.discoverySeeds(profile,"",100,run.snapshot(),
      run.eventAfter(),run.eventThrough()))
      .contains(later.id()));
    assertEquals(run.snapshot(),store.readClusters(repo->repo.run(profile,run.id()).orElseThrow().snapshot()));
    store.writeClusters(repo->{repo.claimRun("snapshot-worker");repo.finishSlice(run.id(),true);return null;});
    var next=store.writeClusters(repo->repo.startRun(profile,"manual"));
    assertEquals(run.eventThrough(),next.eventAfter());
    assertEquals(List.of(later.id()),store.readClusters(repo->repo.discoverySeeds(profile,"",100,
      next.snapshot(),next.eventAfter(),next.eventThrough())));
  }
  @Test void futureValidityBoundaryQueuesDependentClusterAtDueTime() {
    Member a=memory("boundary source");Member b=memory("second source");Member c=memory("third source");
    var group=create("Timed group",a,b,c);
    String cutoff=dev.alvo.pieria.domain.temporal.TemporalSnapshot.timestamp(Instant.now().plusSeconds(3600));
    jdbc.sql("UPDATE validity_segments SET valid_to=? WHERE revision_id IN "
      +"(SELECT id FROM assertion_revisions WHERE memory_id=?)")
      .params(cutoff,a.id()).update();
    assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM cluster_temporal_boundaries WHERE memory_id=? AND boundary_at=?")
      .params(a.id(),cutoff).query(Integer.class).single());
    jdbc.sql("DELETE FROM cluster_dirty WHERE profile_id=?").param(profile).update();

    assertEquals(1,store.<List<ClusterRepository.Run>>writeClusters(repo->repo.activateDueBoundaries(
      Instant.parse(cutoff).plusSeconds(1))).size());
    assertEquals(group.id(),jdbc.sql("SELECT cluster_id FROM cluster_dirty WHERE profile_id=?")
      .param(profile).query(String.class).single());
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM cluster_temporal_boundaries WHERE memory_id=?")
      .param(a.id()).query(Integer.class).single());
  }
  @Test void workerBuildsBroaderContainerFromDistinctTopicClusters() {
    for(int i=0;i<3;i++) memory("storage detail "+i,"pieria:storage");
    for(int i=0;i<3;i++) memory("job detail "+i,"pieria:jobs");
    var generator=new ClusterGenerationService(store,clusterModel(()->{}));
    var worker=new ClusterWorker(store,generator);
    var run=store.writeClusters(repo->repo.startRun(profile,"manual"));
    worker.tick();
    var broader=store.readClusters(repo->repo.byScope(profile,"broader:pieria").orElseThrow());
    assertEquals(2,broader.directCount());
    assertEquals(6,broader.distinctOriginalCount());
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,broader.id(),null).isPresent()));
    assertTrue(jdbc.sql("SELECT COUNT(*) FROM cluster_claims WHERE embedding IS NOT NULL")
      .query(Integer.class).single()>0);
    assertTrue(store.<List<java.util.Map<String,Object>>>readClusters(repo->repo.auditEvents(profile,run.id(),100))
      .stream().anyMatch(event->"work_item".equals(event.get("action"))));
  }
  @Test void higherClusterRejectsTwoWrappersOverTheSameOriginals() {
    Member a=memory("one");Member b=memory("two");Member c=memory("three");
    var first=create("First",a,b,c);var second=create("Second",a,b,c);
    publish(first,List.of(a,b,c),"First summary.");
    publish(second,List.of(a,b,c),"Second summary.");
    var wrapper=create("Redundant wrapper",cluster(first),cluster(second));
    var generator=new ClusterGenerationService(store,clusterModel(()->{}));
    assertThrows(IllegalArgumentException.class,()->generator.generate("graph",wrapper.id(),1));
  }
  @Test void entityDiscoveryRespectsDissolvedGroupProtection() {
    Member a=memory("Alpha storage detail");Member b=memory("Alpha worker detail");Member c=memory("Alpha queue detail");
    jdbc.sql("INSERT INTO entities(id,profile_id,type,name,created_at) VALUES(?,?,'project','alpha',?)")
      .params("entity-alpha",profile,Instant.now().toString()).update();
    int i=0;
    for(Member member:List.of(a,b,c)) jdbc.sql("""
      INSERT INTO edges(id,profile_id,source_entity_id,target_entity_id,relation,memory_id,created_at,recorded_at,recorded_sequence)
      VALUES(?,?,?,?,'related',?,?,?,0)
      """).params("edge-"+(i++),profile,"entity-alpha","entity-alpha",member.id(),Instant.now().toString(),Instant.now().toString()).update();
    var worker=new ClusterWorker(store,new ClusterGenerationService(store,clusterModel(()->{})));
    store.writeClusters(repo->repo.startRun(profile,"manual"));worker.tick();
    var group=store.readClusters(repo->repo.byScope(profile,"entity:entity-alpha").orElseThrow());
    store.writeClusters(repo->repo.dissolve(profile,group.id(),group.revision(),"test"));
    store.writeClusters(repo->repo.startRun(profile,"manual"));worker.tick();
    assertTrue(store.<Boolean>readClusters(repo->repo.byScope(profile,"entity:entity-alpha").isEmpty()));
    assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM clusters WHERE profile_id=?").param(profile).query(Integer.class).single());
  }
  @Test void lexicalDiscoveryGroupsUnkeyedMemoriesWithoutDuplicateWrappers() {
    memory("Alpha subsystem uses a durable queue for worker one");
    memory("Alpha subsystem uses a durable queue for worker two");
    memory("Alpha subsystem uses a durable queue for worker three");
    var worker=new ClusterWorker(store,new ClusterGenerationService(store,clusterModel(()->{})));
    store.writeClusters(repo->repo.startRun(profile,"manual"));worker.tick();
    assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM clusters WHERE profile_id=?")
      .param(profile).query(Integer.class).single());
    assertEquals(1,store.listMemories(profile,MemoryType.CLUSTER,null).size());
  }

  @Test void cyclesRevisionConflictsAndCrossProfileSourcesAreRejected() {
    Member one=memory("one");
    var a=create("A",one);
    var b=create("B",cluster(a));
    assertThrows(IllegalArgumentException.class,()->store.writeClusters(c->c.edit(profile,a.id(),1,"A","scope",List.of(one,cluster(b)),"test","attach")));
    assertThrows(ClusterConflictException.class,()->store.writeClusters(c->c.edit(profile,a.id(),9,"A","scope",List.of(one),"test","rename")));
    String other=store.getOrCreateProfile("other").id();
    Member foreign=new Member("memory",store.insertMemory(other,Memory.of(MemoryType.EVENT,"foreign","f",null,null)).id());
    assertThrows(IllegalArgumentException.class,()->store.writeClusters(c->c.create(profile,"Foreign","scope",List.of(foreign),"test")));
  }
  @Test void concurrentEditsCommitOneRevision() throws Exception {
    Member original=memory("original");Member left=memory("left");Member right=memory("right");
    var group=create("Concurrent",original);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> one=executor.submit(()->editFromRevision(group.id(),original,left));
      Future<Boolean> two=executor.submit(()->editFromRevision(group.id(),original,right));
      assertEquals(1,(one.get()?1:0)+(two.get()?1:0));
    }
    assertEquals(2,store.<Integer>readClusters(repo->repo.detail(profile,group.id(),null).orElseThrow().cluster().revision()).intValue());
  }
  private boolean editFromRevision(String id,Member original,Member added) {
    try {
      store.writeClusters(repo->repo.edit(profile,id,1,"Concurrent","scope",List.of(original,added),"test","attach"));
      return true;
    } catch(ClusterConflictException e) {return false;}
  }

  @Test void detachAndDissolvePreserveOtherParentsAndOriginals() {
    Member one=memory("one");Member two=memory("two");
    var a=create("A",one,two);var b=create("B",one);
    store.writeClusters(c->c.edit(profile,a.id(),1,"A","scope",List.of(two),"test","detach"));
    assertEquals(List.of(one),store.readClusters(c->c.detail(profile,b.id(),null).orElseThrow().members()));
    store.writeClusters(c->c.dissolve(profile,b.id(),1,"test"));
    assertEquals(2,store.listMemories(profile,null,null).size());
    assertEquals("dissolved",store.readClusters(c->c.detail(profile,b.id(),null).orElseThrow().cluster().state()));
    store.writeClusters(c->c.restore(profile,b.id(),2,1,"test"));
    assertEquals("active",store.readClusters(c->c.detail(profile,b.id(),null).orElseThrow().cluster().state()));
  }

  @Test void directAndParentBoundsAreEnforced() {
    List<Member> many=new ArrayList<>();
    for(int i=0;i<21;i++) many.add(memory("source "+i));
    assertThrows(IllegalArgumentException.class,()->store.writeClusters(c->c.create(profile,"Too many","scope",many,"test")));
    for(int i=0;i<5;i++) create("parent "+i,many.getFirst());
    assertThrows(IllegalArgumentException.class,()->create("sixth",many.getFirst()));
  }

  @Test void claimSupportIsDeduplicatedAndForgettingInvalidatesPublishedSynthesis() {
    Member a=memory("SQLite permits one writer");
    Member b=memory("The queue persists pending work");
    Member c=memory("A worker resumes pending work");
    var group=create("Reliable jobs",a,b,c);
    var sources=List.of(a,b,c).stream().map(member -> new ClusterRepository.Source(member.id(),
      jdbc.sql("SELECT id FROM assertion_revisions WHERE memory_id=?").param(member.id()).query(String.class).single())).toList();
    var claim=new ClusterRepository.Claim("The durable queue coordinates jobs.",sources,sources);
    String fingerprint=store.readClusters(repo->repo.fingerprint(profile,group.id()));
    var synthesis=store.writeClusters(repo->repo.publish(profile,group.id(),1,fingerprint,"The durable queue coordinates jobs.",
      List.of(claim),"fake-large","v1","{}","test"));
    assertEquals(3,synthesis.claims().getFirst().supports().size());
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,group.id(),null).isPresent()));
    assertEquals(1,store.listMemories(profile,MemoryType.CLUSTER,null).size());
    assertEquals(1,store.searchClusterClaims(profile,"durable queue",store.temporalSnapshot(null,null),false,10).size());
    store.forgetMemory(profile,a.id());
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,group.id(),null).isEmpty()));
    assertTrue(store.listMemories(profile,MemoryType.CLUSTER,null).isEmpty());
    assertTrue(store.searchClusterClaims(profile,"durable queue",store.temporalSnapshot(null,null),false,10).isEmpty());
    assertTrue(store.searchMemoriesFts(profile,"durable queue",1).stream()
      .noneMatch(memory->memory.type()==MemoryType.CLUSTER));
    assertTrue(store.exportProfile(profile).stream().noneMatch(row->row.memory().type()==MemoryType.CLUSTER));
    var archive=store.readClusters(repo->repo.exportProfile(profile));
    assertEquals(1,((List<?>)archive.get("clusters")).size());
    assertTrue(archive.toString().contains("source_or_revision_unavailable"));
    assertFalse(archive.toString().contains("The durable queue coordinates jobs."));
  }
  @Test void ftsKeepsFetchingWhenStaleClusterRowsFillTheFirstPage() {
    for(int i=0;i<25;i++) store.insertMemory(profile,Memory.of(MemoryType.CLUSTER,
      "needle "+i,"stale-"+i,null,null));
    Member original=memory("needle original evidence with many distinct supporting words and details");
    var firstPage=jdbc.sql("""
      SELECT m.type FROM memories_fts f JOIN memories m ON m.rowid=f.rowid
      WHERE memories_fts MATCH 'needle' AND m.profile_id=? ORDER BY f.rank,m.id LIMIT 20
      """).param(profile).query(String.class).list();
    assertEquals(java.util.Set.of("cluster"),java.util.Set.copyOf(firstPage));
    assertEquals(original.id(),store.searchMemoriesFts(profile,"needle",1).getFirst().id());
  }
  @Test void sourceChangeQueuesEveryAncestorBottomUp() {
    Member a=memory("alpha");Member b=memory("bravo");Member c=memory("charlie");
    Member d=memory("delta");Member e=memory("echo");Member f=memory("foxtrot");
    var child=create("Child",a,b,c);
    publish(child,List.of(a,b,c),"The child covers three sources.");
    var parent=create("Parent",cluster(child),d,e,f);
    publish(parent,List.of(a,b,c,d,e,f),"The parent covers two contributions.");
    assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM cluster_dependencies WHERE descendant_id=?")
      .param(a.id()).query(Integer.class).single());
    store.forgetMemory(profile,a.id());
    assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM cluster_dirty WHERE reason='forgotten'")
      .query(Integer.class).single());
    var run=store.writeClusters(repo->repo.startRun(profile,"manual"));
    store.writeClusters(repo->{repo.enqueueDirtyRegenerations(run.id(),profile);return null;});
    var queued=store.readClusters(repo->repo.pendingRegenerations(run.id(),10));
    assertEquals(List.of(child.id(),parent.id()),queued.stream().map(ClusterRepository.Regeneration::clusterId).toList());
  }
  @Test void historicalSnapshotKeepsItsPinnedStructureAndSynthesis() {
    Member a=memory("alpha");Member b=memory("bravo");Member c=memory("charlie");
    var group=create("Historical",a,b,c);
    String oldMemory=publish(group,List.of(a,b,c),"The historical group has three sources.");
    var snapshot=store.temporalSnapshot(null,null);
    Member later=memory("delta");
    store.writeClusters(repo->repo.edit(profile,group.id(),1,"Historical","scope",
      List.of(a,b,c,later),"test","attach"));
    assertTrue(store.<Boolean>readClusters(repo->repo.synthesis(profile,group.id(),null).isEmpty()));
    assertEquals(1,store.<Integer>readClusters(repo->repo.detail(profile,group.id(),snapshot).orElseThrow().cluster().revision()).intValue());
    assertTrue(store.eligibleClusterMemory(profile,oldMemory,snapshot,false));
    assertFalse(store.eligibleClusterMemory(profile,oldMemory,store.temporalSnapshot(null,null),false));
  }

  ModelGateway clusterModel(Runnable duringSynthesis) {
    return new ModelGateway() {
      @Override public float[] embed(String text) {return new float[]{1,0,0};}
      @Override public String synthesizeRecall(String query,List<dev.alvo.pieria.retrieval.model.RecallCandidate> candidates) {return "";}
      @Override public boolean validateClusterGroup(String title,String scope,List<String> originals) {return true;}
      @Override public boolean validateClusterClaim(String claim,List<String> originals) {return true;}
      @Override public boolean validateClusterCoverage(String original,String claim) {return true;}
      @Override public ClusterModelInput.Draft synthesizeCluster(ClusterModelInput input) {
        duringSynthesis.run();
        var ids=input.evidence().stream().map(ClusterRepository.Evidence::memoryId).toList();
        return new ClusterModelInput.Draft("summary",List.of(new ClusterModelInput.DraftClaim(
          "The sources describe a coordinated system.",ids,ids)));
      }
    };
  }

  @Test void sourceChangeDuringModelCallPreventsPublication() {
    Member a=memory("first");Member b=memory("second");Member c=memory("third");
    var group=create("Group",a,b,c);
    var generator=new ClusterGenerationService(store,clusterModel(()->store.forgetMemory(profile,a.id())));
    assertThrows(ClusterConflictException.class,()->generator.generate("graph",group.id(),1));
    assertTrue(store.listMemories(profile,MemoryType.CLUSTER,null).isEmpty());
  }

  @Test void generationDefersSourcesAddedAfterRunSnapshot() {
    Member a=memory("first");Member b=memory("second");Member c=memory("third");
    var group=create("Group",a,b,c);
    var run=store.writeClusters(repo->repo.startRun(profile,"regenerate"));
    Member later=memory("a later source");
    store.writeClusters(repo->repo.edit(profile,group.id(),group.revision(),group.title(),
      group.scope(),List.of(a,b,c,later),"manual","attach"));
    var generator=new ClusterGenerationService(store,clusterModel(()->{}));
    var error=assertThrows(ClusterConflictException.class,()->generator.generateForProfileId(
      profile,group.id(),2,run.id(),"worker"));
    assertEquals("source changed after run snapshot",error.getMessage());
    assertTrue(store.listMemories(profile,MemoryType.CLUSTER,null).isEmpty());
  }

  @Test void pauseFencesInFlightGenerationAndResumeKeepsRun() {
    Member a=memory("first");Member b=memory("second");Member c=memory("third");
    var group=create("Group",a,b,c);
    var run=store.writeClusters(repo->repo.startRun(profile,"regenerate"));
    store.writeClusters(repo->repo.claimRun("worker"));
    var generator=new ClusterGenerationService(store,clusterModel(()->store.writeClusters(repo->repo.transitionRun(profile,run.id(),"pause"))));
    assertThrows(ClusterConflictException.class,()->generator.generateForProfileId(profile,group.id(),1,run.id(),"worker"));
    assertEquals("paused",store.readClusters(repo->repo.run(profile,run.id()).orElseThrow().state()));
    assertEquals("queued",store.writeClusters(repo->repo.transitionRun(profile,run.id(),"resume")).state());
  }
  @Test void expiredWorkerCannotReclaimLeaseAfterTakeover() {
    var run=store.writeClusters(repo->repo.startRun(profile,"manual"));
    store.writeClusters(repo->repo.claimRun("old-worker"));
    jdbc.sql("UPDATE cluster_runs SET lease_until=? WHERE id=?")
      .params("2000-01-01T00:00:00Z",run.id()).update();
    assertEquals(run.id(),store.writeClusters(repo->repo.claimRun("new-worker")).orElseThrow().id());
    assertThrows(ClusterConflictException.class,()->store.writeClusters(repo->{repo.renewRun(run.id(),"old-worker");return null;}));
    assertFalse(store.<Boolean>readClusters(repo->repo.canPublishRun(profile,run.id(),"old-worker")));
    assertTrue(store.<Boolean>readClusters(repo->repo.canPublishRun(profile,run.id(),"new-worker")));
  }
  @Test void onlyOneRunCanOwnTheGlobalGenerationLane() {
    String other=store.getOrCreateProfile("other-lane").id();
    store.writeClusters(repo->repo.startRun(profile,"manual"));
    store.writeClusters(repo->repo.startRun(other,"manual"));
    var first=store.writeClusters(repo->repo.claimRun("worker-a")).orElseThrow();
    assertTrue(store.<java.util.Optional<ClusterRepository.Run>>writeClusters(repo->repo.claimRun("worker-b")).isEmpty());
    store.writeClusters(repo->repo.transitionRun(first.profileId(),first.id(),"pause"));
    assertTrue(store.<java.util.Optional<ClusterRepository.Run>>writeClusters(repo->repo.claimRun("worker-b")).isPresent());
  }

  @Test void profileDeletionRemovesGraphAndRunRecords() {
    var group=create("Group",memory("a"),memory("b"),memory("c"));
    store.writeClusters(repo->repo.startRun(profile,"manual"));
    store.deleteProfile(profile);
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM clusters").query(Integer.class).single());
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM cluster_runs").query(Integer.class).single());
    assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM cluster_discovery_changes").query(Integer.class).single());
  }

  @Test void scheduleDeduplicatesLocalDateAndDoesNotOverridePause() {
    store.writeClusters(repo->repo.setSchedule(profile,true,"02:00","UTC"));
    Instant first=Instant.parse("2026-10-17T02:00:30Z");
    assertEquals(1,store.<List<ClusterRepository.Run>>writeClusters(repo->repo.activateDueSchedules(first)).size());
    assertTrue(store.<List<ClusterRepository.Run>>writeClusters(repo->repo.activateDueSchedules(first)).isEmpty());
    var run=store.readClusters(repo->repo.runs(profile,1).getFirst());
    store.writeClusters(repo->repo.transitionRun(profile,run.id(),"pause"));
    assertTrue(store.<List<ClusterRepository.Run>>writeClusters(repo->repo.activateDueSchedules(first.plusSeconds(86400))).isEmpty());
    assertEquals("paused",store.readClusters(repo->repo.run(profile,run.id()).orElseThrow().state()));
  }
}
