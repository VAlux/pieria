package dev.alvo.pieria.cluster;

import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.tools.Hash;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

import static dev.alvo.pieria.cluster.ClusterRepository.*;

/** SQLite implementation. Call mutations only through MemoryStore.writeClusters. */
public final class SqliteClusterStore implements ClusterRepository {
  private final JdbcClient jdbc;
  private final MemoryStore memoryStore;
  private final ObjectMapper json=new ObjectMapper();
  public SqliteClusterStore(JdbcClient jdbc, MemoryStore memoryStore) {
    this.jdbc = jdbc;
    this.memoryStore = memoryStore;
  }

  private record Head(String id, String profile, int revision, String title, String scope,
                      String state, String recordedAt) {}

  private long sequence() {
    jdbc.sql("INSERT INTO temporal_transactions(recorded_at) VALUES (?)")
      .param(TemporalSnapshot.timestamp(Instant.now())).update();
    return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
  }

  private Optional<Head> head(String profileId, String id, TemporalSnapshot snapshot) {
    String sql = """
      SELECT c.id,c.profile_id,s.revision,s.title,s.scope,s.state,s.recorded_at
      FROM clusters c JOIN cluster_structure_revisions s ON s.cluster_id=c.id
      WHERE c.profile_id=? AND c.id=?
      """;
    if (snapshot == null) sql += " AND s.revision=c.current_revision";
    else sql += " AND s.recorded_at<=? AND s.recorded_sequence<=? ORDER BY s.revision DESC LIMIT 1";
    var spec = jdbc.sql(sql).params(profileId,id);
    if (snapshot != null) spec = spec.params(TemporalSnapshot.timestamp(snapshot.knownAsOf()),snapshot.sequence());
    return spec.query((rs, _) -> new Head(rs.getString(1),rs.getString(2),rs.getInt(3),
      rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7))).optional();
  }

  private Head require(String profileId, String id, int expected) {
    Head head = head(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
    if (head.revision != expected) throw new ClusterConflictException("expected revision " + expected + ", current revision " + head.revision);
    return head;
  }

  private List<Member> members(String id, int revision) {
    return jdbc.sql("SELECT child_kind,child_id FROM cluster_memberships WHERE cluster_id=? AND revision=? ORDER BY child_kind,child_id")
      .params(id,revision).query((rs, _) -> new Member(rs.getString(1),rs.getString(2))).list();
  }

  private void validateMembers(String profileId, String clusterId, List<Member> proposed) {
    if (proposed == null || proposed.size() > 20 || new HashSet<>(proposed).size() != proposed.size())
      throw new IllegalArgumentException("direct members must be unique and at most 20");
    for (Member member : proposed) {
      if (member.kind().equals("cluster")) {
        if (member.id().equals(clusterId)) throw new IllegalArgumentException("self membership");
        Head child = head(profileId,member.id(),null).orElseThrow(() -> new IllegalArgumentException("child cluster not found in profile"));
        if (!child.state.equals("active")) throw new IllegalArgumentException("dissolved child cluster");
      } else {
        Integer exists = jdbc.sql("SELECT COUNT(*) FROM memories WHERE profile_id=? AND id=? AND forgotten=0 AND type<>'cluster'")
          .params(profileId,member.id()).query(Integer.class).single();
        if (exists == 0) throw new IllegalArgumentException("source memory not found in profile");
      }
    }
    Map<String,List<Member>> graph = activeGraph(profileId);
    graph.put(clusterId,List.copyOf(proposed));
    Map<Member,Integer> parentCounts = new HashMap<>();
    for (var entry : graph.entrySet()) for (Member member : entry.getValue()) {
      int count = parentCounts.merge(member,1,Integer::sum);
      if (count > 5) throw new IllegalArgumentException("node exceeds five direct parents");
    }
    for (String node : graph.keySet()) {
      Set<String> originals = new HashSet<>();
      int depth = walk(node,graph,new HashSet<>(),originals);
      if (depth > 4) throw new IllegalArgumentException("cluster graph exceeds four levels");
      if (originals.size() > 500) throw new IllegalArgumentException("cluster exceeds 500 distinct descendants");
    }
  }

  private Map<String,List<Member>> activeGraph(String profileId) {
    Map<String,List<Member>> graph = new HashMap<>();
    List<String> ids = jdbc.sql("""
      SELECT c.id FROM clusters c JOIN cluster_structure_revisions s
      ON s.cluster_id=c.id AND s.revision=c.current_revision
      WHERE c.profile_id=? AND s.state='active'
      """).param(profileId).query(String.class).list();
    for (String id : ids) {
      int revision = head(profileId,id,null).orElseThrow().revision;
      graph.put(id,members(id,revision));
    }
    return graph;
  }

  private int walk(String node, Map<String,List<Member>> graph, Set<String> path, Set<String> originals) {
    if (!path.add(node)) throw new IllegalArgumentException("cluster cycle");
    int depth = 1;
    for (Member child : graph.getOrDefault(node,List.of())) {
      if (child.kind().equals("memory")) originals.add(child.id());
      else depth = Math.max(depth,1+walk(child.id(),graph,path,originals));
    }
    path.remove(node);
    return depth;
  }

  private void revision(String profileId, String id, int revision, String title, String scope,
                        String state, List<Member> members, String actor, String reason) {
    String now = TemporalSnapshot.timestamp(Instant.now());
    long sequence = sequence();
    jdbc.sql("INSERT INTO cluster_structure_revisions(cluster_id,revision,title,scope,state,recorded_at,recorded_sequence,actor,reason) VALUES (?,?,?,?,?,?,?,?,?)")
      .params(id,revision,title,scope,state,now,sequence,actor,reason).update();
    for (Member member : members) jdbc.sql("INSERT INTO cluster_memberships(cluster_id,revision,child_kind,child_id) VALUES (?,?,?,?)")
      .params(id,revision,member.kind(),member.id()).update();
    jdbc.sql("UPDATE clusters SET current_revision=? WHERE id=? AND profile_id=?")
      .params(revision,id,profileId).update();
    refreshDependencies(profileId,id);
    audit(profileId,id,actor,reason,"{\"revision\":"+revision+"}");
  }

  private void refreshDependencies(String profileId,String changedId) {
    Map<String,List<Member>> graph=activeGraph(profileId);
    Set<String> affected=new HashSet<>(List.of(changedId));
    boolean grew;
    do {
      grew=false;
      for(var entry:graph.entrySet()) if(entry.getValue().stream().anyMatch(member->
          member.kind().equals("cluster") && affected.contains(member.id())))
        grew|=affected.add(entry.getKey());
    } while(grew);
    for(String ancestor:affected) {
      jdbc.sql("DELETE FROM cluster_dependencies WHERE profile_id=? AND ancestor_id=?")
        .params(profileId,ancestor).update();
      if(!graph.containsKey(ancestor)) continue;
      Set<Member> descendants=new HashSet<>();
      collectDependencies(ancestor,graph,descendants,new HashSet<>());
      for(Member descendant:descendants) jdbc.sql("INSERT INTO cluster_dependencies(profile_id,ancestor_id,descendant_kind,descendant_id) VALUES(?,?,?,?)")
        .params(profileId,ancestor,descendant.kind(),descendant.id()).update();
      jdbc.sql("INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at) VALUES(?,?,?,?) "
        +"ON CONFLICT(profile_id,cluster_id) DO UPDATE SET reason=excluded.reason,recorded_at=excluded.recorded_at")
        .params(profileId,ancestor,"structure",TemporalSnapshot.timestamp(Instant.now())).update();
    }
  }
  private void collectDependencies(String id,Map<String,List<Member>> graph,Set<Member> found,Set<String> visited) {
    if(!visited.add(id)) return;
    for(Member member:graph.getOrDefault(id,List.of())) {
      found.add(member);
      if(member.kind().equals("cluster")) collectDependencies(member.id(),graph,found,visited);
    }
  }

  private void audit(String profileId,String id,String actor,String action,String detail) {
    jdbc.sql("INSERT INTO cluster_audit_events(id,profile_id,cluster_id,actor,trigger_name,action,detail,recorded_at) VALUES (?,?,?,?,?,?,?,?)")
      .params(UUID.randomUUID().toString(),profileId,id,actor,actor,action,detail,Instant.now().toString()).update();
  }
  private void auditRun(String runId,String clusterId,String action,Map<String,?> detail) {
    Run run=jdbc.sql("SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs WHERE id=?")
      .param(runId).query((r,n)->mapRun(r)).single();
    jdbc.sql("INSERT INTO cluster_audit_events(id,profile_id,cluster_id,run_id,actor,trigger_name,action,detail,recorded_at) VALUES (?,?,?,?,?,?,?,?,?)")
      .params(UUID.randomUUID().toString(),run.profileId(),clusterId,runId,"worker",run.trigger(),action,
        json.writeValueAsString(detail),Instant.now().toString()).update();
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name+" is required");
    return value.strip();
  }

  @Override public Cluster create(String profileId,String title,String scope,List<Member> members,String actor) {
    String id=UUID.randomUUID().toString();
    title=required(title,"title"); scope=required(scope,"scope");
    if("automatic".equals(actor) && protectedGroup(profileId,members))
      throw new ClusterConflictException("manually dissolved group is protected");
    validateMembers(profileId,id,members);
    jdbc.sql("INSERT INTO clusters(id,profile_id,current_revision,created_at) VALUES (?,?,1,?)")
      .params(id,profileId,Instant.now().toString()).update();
    revision(profileId,id,1,title,scope,"active",members,actor,"create");
    return detail(profileId,id,null).orElseThrow().cluster();
  }

  @Override public Cluster edit(String profileId,String clusterId,int expectedRevision,String title,String scope,
                                List<Member> members,String actor,String reason) {
    Head prior=require(profileId,clusterId,expectedRevision);
    if (!prior.state.equals("active")) throw new IllegalArgumentException("dissolved cluster");
    title=required(title,"title"); scope=required(scope,"scope");
    validateMembers(profileId,clusterId,members);
    List<Member> old=members(clusterId,prior.revision);
    if (old.equals(members) && prior.title.equals(title) && prior.scope.equals(scope))
      return detail(profileId,clusterId,null).orElseThrow().cluster();
    revision(profileId,clusterId,expectedRevision+1,title,scope,"active",members,actor,reason);
    if (!"automatic".equals(actor)) {
      for (Member removed : old) if (!members.contains(removed)) override(profileId,clusterId,removed,"excluded");
      for (Member added : members) if (!old.contains(added)) override(profileId,clusterId,added,"pinned");
    }
    return detail(profileId,clusterId,null).orElseThrow().cluster();
  }

  private void override(String profileId,String clusterId,Member member,String decision) {
    jdbc.sql("""
      INSERT INTO cluster_overrides(profile_id,cluster_id,child_kind,child_id,decision,recorded_at)
      VALUES(?,?,?,?,?,?) ON CONFLICT(cluster_id,child_kind,child_id)
      DO UPDATE SET decision=excluded.decision,recorded_at=excluded.recorded_at
      """).params(profileId,clusterId,member.kind(),member.id(),decision,Instant.now().toString()).update();
  }

  @Override public Cluster dissolve(String profileId,String clusterId,int expectedRevision,String actor) {
    Head prior=require(profileId,clusterId,expectedRevision);
    if (!prior.state.equals("active")) throw new IllegalArgumentException("already dissolved");
    List<Member> current=members(clusterId,expectedRevision);
    Member dissolved=new Member("cluster",clusterId);
    List<String> parentIds=activeGraph(profileId).entrySet().stream()
      .filter(entry->entry.getValue().contains(dissolved)).map(Map.Entry::getKey)
      .sorted().toList();
    revision(profileId,clusterId,expectedRevision+1,prior.title,prior.scope,"dissolved",current,actor,"dissolve");
    for(String parentId:parentIds) {
      Head parent=head(profileId,parentId,null).orElseThrow();
      List<Member> remaining=members(parentId,parent.revision).stream()
        .filter(member->!member.equals(dissolved)).toList();
      revision(profileId,parentId,parent.revision+1,parent.title,parent.scope,"active",remaining,
        actor,"dissolved-child-detach");
      override(profileId,parentId,dissolved,"excluded");
    }
    String fingerprint=current.toString();
    jdbc.sql("INSERT OR REPLACE INTO cluster_dissolved_groups(profile_id,fingerprint,cluster_id,recorded_at) VALUES (?,?,?,?)")
      .params(profileId,fingerprint,clusterId,Instant.now().toString()).update();
    return detail(profileId,clusterId,null).orElseThrow().cluster();
  }

  @Override public Cluster restore(String profileId,String clusterId,int expectedRevision,int sourceRevision,String actor) {
    Head prior=require(profileId,clusterId,expectedRevision);
    Head earlier=jdbc.sql("""
      SELECT c.id,c.profile_id,s.revision,s.title,s.scope,s.state,s.recorded_at
      FROM clusters c JOIN cluster_structure_revisions s ON s.cluster_id=c.id
      WHERE c.id=? AND c.profile_id=? AND s.revision=?
      """).params(clusterId,profileId,sourceRevision)
      .query((rs,_) -> new Head(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)))
      .optional().orElseThrow(() -> new IllegalArgumentException("revision not found"));
    List<Member> restored=members(clusterId,sourceRevision);
    validateMembers(profileId,clusterId,restored);
    Map<String,List<Member>> proposed=activeGraph(profileId);
    proposed.put(clusterId,restored);
    Set<String> originals=new HashSet<>();
    walk(clusterId,proposed,new HashSet<>(),originals);
    TemporalSnapshot snapshot=memoryStore.temporalSnapshot(null,null);
    for(String memoryId:originals)
      if(memoryStore.selectedValidity(memoryId,snapshot).isEmpty())
        throw new ClusterConflictException("restored source is not currently valid: "+memoryId);
    revision(profileId,clusterId,expectedRevision+1,earlier.title,earlier.scope,"active",restored,actor,"restore");
    jdbc.sql("DELETE FROM cluster_dissolved_groups WHERE profile_id=? AND cluster_id=?")
      .params(profileId,clusterId).update();
    return detail(profileId,clusterId,null).orElseThrow().cluster();
  }

  private List<String> parents(String profileId,String childId,TemporalSnapshot snapshot) {
    List<String> result=new ArrayList<>();
    for(String id:jdbc.sql("SELECT id FROM clusters WHERE profile_id=?").param(profileId).query(String.class).list()) {
      Head h=head(profileId,id,snapshot).orElse(null);
      if(h!=null && h.state.equals("active") && members(id,h.revision).contains(new Member("cluster",childId))) result.add(id);
    }
    result.sort(String::compareTo);
    return result;
  }

  @Override public Optional<Detail> detail(String profileId,String id,TemporalSnapshot snapshot) {
    Head head=head(profileId,id,snapshot).orElse(null);
    if(head==null) return Optional.empty();
    List<Member> direct=members(id,head.revision);
    Set<String> originals=new HashSet<>();
    Map<String,List<Member>> graph=activeGraph(profileId);
    if(snapshot!=null) graph=snapshotGraph(profileId,snapshot);
    walk(id,graph,new HashSet<>(),originals);
    boolean fresh=synthesis(profileId,id,snapshot).isPresent();
    Cluster cluster=new Cluster(id,profileId,head.revision,head.title,head.scope,head.state,
      direct.size(),originals.size(),fresh?"current":"pending");
    List<Structure> history=jdbc.sql("SELECT revision,title,scope,state,recorded_at,actor,reason FROM cluster_structure_revisions WHERE cluster_id=? ORDER BY revision DESC")
      .param(id).query((rs,_) -> new Structure(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4),
        rs.getString(5),rs.getString(6),rs.getString(7),members(id,rs.getInt(1)))).list();
    return Optional.of(new Detail(cluster,direct,parents(profileId,id,snapshot),history));
  }

  private Map<String,List<Member>> snapshotGraph(String profileId,TemporalSnapshot snapshot) {
    Map<String,List<Member>> graph=new HashMap<>();
    for(String id:jdbc.sql("SELECT id FROM clusters WHERE profile_id=?").param(profileId).query(String.class).list()) {
      Head h=head(profileId,id,snapshot).orElse(null);
      if(h!=null && h.state.equals("active")) graph.put(id,members(id,h.revision));
    }
    return graph;
  }

  @Override public List<Cluster> list(String profileId,String query,int limit,TemporalSnapshot snapshot) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    List<Cluster> result=new ArrayList<>();
    for(String id:jdbc.sql("SELECT id FROM clusters WHERE profile_id=? ORDER BY created_at DESC")
      .param(profileId).query(String.class).list()) {
      Detail detail=detail(profileId,id,snapshot).orElse(null);
      if(detail!=null && detail.cluster().state().equals("active") &&
        (query==null || (detail.cluster().title()+" "+detail.cluster().scope()).toLowerCase().contains(query.toLowerCase()))) {
        result.add(detail.cluster());
        if(result.size()==limit) break;
      }
    }
    return result;
  }

  @Override public List<Member> descendants(String profileId,String clusterId,int offset,int limit,TemporalSnapshot snapshot) {
    if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("invalid page");
    Head head=head(profileId,clusterId,snapshot).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
    Map<String,List<Member>> graph=snapshot==null?activeGraph(profileId):snapshotGraph(profileId,snapshot);
    Set<Member> found=new LinkedHashSet<>();
    collect(clusterId,graph,found,new HashSet<>());
    return found.stream().sorted(Comparator.comparing(Member::kind).thenComparing(Member::id)).skip(offset).limit(limit).toList();
  }

  private void collect(String id,Map<String,List<Member>> graph,Set<Member> found,Set<String> visited) {
    if(!visited.add(id)) return;
    for(Member member:graph.getOrDefault(id,List.of())) {
      found.add(member);
      if(member.kind().equals("cluster")) collect(member.id(),graph,found,visited);
    }
  }

  @Override public List<Evidence> evidence(String profileId,String clusterId,TemporalSnapshot snapshot) {
    Head head=head(profileId,clusterId,snapshot).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
    if(!head.state.equals("active")) throw new IllegalArgumentException("dissolved cluster");
    Set<String> ids=new LinkedHashSet<>();
    // The 500-source limit does not bound the number of intermediate clusters.
    // Continue until the traversal is exhausted so a dense DAG cannot hide sources.
    for(int offset=0;;offset+=100) {
      List<Member> page=descendants(profileId,clusterId,offset,100,snapshot);
      for(Member member:page) if(member.kind().equals("memory")) ids.add(member.id());
      if(page.size()<100) break;
    }
    Instant world=snapshot==null?Instant.now():snapshot.asOf();
    Instant known=snapshot==null?Instant.now():snapshot.knownAsOf();
    long sequence=snapshot==null?Long.MAX_VALUE:snapshot.sequence();
    List<Evidence> evidence=new ArrayList<>();
    for(String id:ids) {
      var selected=jdbc.sql("""
        SELECT m.id,ar.id,m.content,m.session_id FROM memories m
        JOIN assertion_revisions ar ON ar.memory_id=m.id
        JOIN validity_segments v ON v.revision_id=ar.id
        WHERE m.id=? AND m.profile_id=? AND m.forgotten=0 AND m.type<>'cluster'
        AND v.valid_from<=? AND (v.valid_to IS NULL OR v.valid_to>?)
        AND v.recorded_from<=? AND (v.recorded_to IS NULL OR v.recorded_to>?)
        AND v.from_sequence<=? AND (v.to_sequence IS NULL OR v.to_sequence>?)
        ORDER BY v.from_sequence DESC LIMIT 1
        """).params(id,profileId,TemporalSnapshot.timestamp(world),TemporalSnapshot.timestamp(world),
          TemporalSnapshot.timestamp(known),TemporalSnapshot.timestamp(known),sequence,sequence)
        .query((rs,_) -> new Evidence(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))).optional();
      selected.ifPresent(evidence::add);
    }
    evidence.sort(Comparator.comparing(Evidence::memoryId));
    return evidence;
  }

  @Override public String fingerprint(String profileId,String clusterId) {
    Head head=head(profileId,clusterId,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
    List<String> parts=new ArrayList<>(List.of(clusterId,Integer.toString(head.revision)));
    for(Evidence source:evidence(profileId,clusterId,null)) {
      parts.add(source.memoryId()); parts.add(source.assertionRevisionId());
    }
    for(Member member:members(clusterId,head.revision)) if(member.kind().equals("cluster")) {
      Synthesis child=synthesis(profileId,member.id(),null)
        .orElseThrow(() -> new ClusterConflictException("child synthesis unavailable"));
      parts.add(member.id());parts.add(child.id());
    }
    StringBuilder canonical=new StringBuilder();
    for(String part:parts) canonical.append(part.length()).append(':').append(part);
    return Hash.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Override public Optional<Synthesis> synthesis(String profileId,String clusterId,TemporalSnapshot snapshot) {
    Head head=head(profileId,clusterId,snapshot).orElse(null);
    if(head==null || !head.state.equals("active")) return Optional.empty();
    String sql="""
      SELECT id,structure_revision,body,fingerprint,status FROM cluster_syntheses
      WHERE cluster_id=? AND structure_revision=? AND status='published'
      """;
    if(snapshot!=null) sql+=" AND recorded_at<=? AND recorded_sequence<=?";
    sql+=" ORDER BY recorded_sequence DESC LIMIT 1";
    var spec=jdbc.sql(sql).params(clusterId,head.revision);
    if(snapshot!=null) spec=spec.params(TemporalSnapshot.timestamp(snapshot.knownAsOf()),snapshot.sequence());
    return spec.query((rs,_) -> new Synthesis(rs.getString(1),clusterId,rs.getInt(2),rs.getString(3),
      rs.getString(4),rs.getString(5),claims(rs.getString(1)))).optional()
      .filter(s -> eligible(profileId,s,snapshot,new HashSet<>()));
  }

  public boolean eligibleMemory(String profileId,String memoryId,TemporalSnapshot snapshot,
                                boolean excludeCodeDerived) {
    String clusterId=jdbc.sql("""
      SELECT s.cluster_id FROM cluster_syntheses s JOIN clusters c ON c.id=s.cluster_id
      WHERE c.profile_id=? AND s.memory_id=?
      """).params(profileId,memoryId).query(String.class).optional().orElse(null);
    if(clusterId==null) return false;
    Synthesis current=synthesis(profileId,clusterId,snapshot).orElse(null);
    if(current==null) return false;
    String actual=jdbc.sql("SELECT id FROM cluster_syntheses WHERE memory_id=?")
      .param(memoryId).query(String.class).single();
    if(!actual.equals(current.id())) return false;
    if(excludeCodeDerived) {
      Integer code=jdbc.sql("""
        SELECT COUNT(*) FROM cluster_claim_evidence ce JOIN cluster_claims cc ON cc.id=ce.claim_id
        JOIN memories m ON m.id=ce.memory_id WHERE cc.synthesis_id=? AND m.session_id=?
        """).params(actual,CodeIndexingService.CODE_SESSION).query(Integer.class).single();
      if(code>0) return false;
    }
    return true;
  }

  public Set<String> coveredOriginals(String profileId,String memoryId,String query,TemporalSnapshot snapshot) {
    String clusterId=jdbc.sql("SELECT cluster_id FROM cluster_syntheses WHERE memory_id=?")
      .param(memoryId).query(String.class).optional().orElse(null);
    if(clusterId==null) return Set.of();
    Synthesis synthesis=synthesis(profileId,clusterId,snapshot).orElse(null);
    if(synthesis==null) return Set.of();
    String actual=jdbc.sql("SELECT id FROM cluster_syntheses WHERE memory_id=?")
      .param(memoryId).query(String.class).single();
    if(!actual.equals(synthesis.id())) return Set.of();
    Set<String> terms=queryTerms(query);
    if(terms.isEmpty()) return Set.of();
    Set<String> covered=new HashSet<>();
    for(Claim claim:synthesis.claims()) {
      String text=claim.text().toLowerCase();
      if(terms.stream().noneMatch(text::contains)) continue;
      for(Source source:claim.covers()) covered.add(source.memoryId());
    }
    return covered;
  }

  private static Set<String> queryTerms(String query) {
    if(query==null) return Set.of();
    Set<String> terms=new HashSet<>();
    for(String token:query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
      if(token.length()>=3 && !Set.of("the","and","for","with","from","what","how","are",
          "was","this","that","does","can").contains(token)) terms.add(token);
    return terms;
  }

  /** Nominate only parents with matching claims or evidence from separate child branches. */
  public List<String> relevantParents(String profileId,List<String> seedMemoryIds,String query,
                                      TemporalSnapshot snapshot,int limit) {
    if(seedMemoryIds.isEmpty() || limit<1) return List.of();
    Map<String,List<Member>> graph=snapshot==null?activeGraph(profileId):snapshotGraph(profileId,snapshot);
    Map<Member,List<String>> reverse=new HashMap<>();
    graph.forEach((parent,members)->members.forEach(member->
      reverse.computeIfAbsent(member,ignored->new ArrayList<>()).add(parent)));
    reverse.values().forEach(parents->parents.sort(String::compareTo));
    Set<String> originals=new HashSet<>();
    Set<Member> frontier=new LinkedHashSet<>();
    for(String memoryId:seedMemoryIds) {
      String clusterId=jdbc.sql("SELECT s.cluster_id FROM cluster_syntheses s JOIN clusters c ON c.id=s.cluster_id WHERE s.memory_id=? AND c.profile_id=?")
        .params(memoryId,profileId).query(String.class).optional().orElse(null);
      if(clusterId==null) { originals.add(memoryId);frontier.add(new Member("memory",memoryId)); }
      else {
        frontier.add(new Member("cluster",clusterId));
        coveredOriginals(profileId,memoryId,query,snapshot).forEach(originals::add);
      }
    }
    Set<String> candidates=new LinkedHashSet<>();
    for(int level=0;level<4 && !frontier.isEmpty() && candidates.size()<30;level++) {
      Set<Member> next=new LinkedHashSet<>();
      for(Member child:frontier) for(String parent:reverse.getOrDefault(child,List.of()))
        if(candidates.add(parent)) next.add(new Member("cluster",parent));
      frontier=next;
    }
    Set<String> terms=queryTerms(query);
    List<String> result=new ArrayList<>();
    for(String id:candidates) {
      Synthesis synthesis=synthesis(profileId,id,snapshot).orElse(null);
      if(synthesis==null) continue;
      boolean claimMatch=synthesis.claims().stream().anyMatch(claim->
        terms.stream().anyMatch(claim.text().toLowerCase()::contains));
      int branches=0;
      if(!claimMatch) for(Member member:graph.getOrDefault(id,List.of())) {
        Set<String> branch=new HashSet<>();
        if(member.kind().equals("memory")) branch.add(member.id());
        else walk(member.id(),graph,new HashSet<>(),branch);
        if(branch.stream().anyMatch(originals::contains)) branches++;
      }
      if(!claimMatch && branches<2) continue;
      String memory=jdbc.sql("SELECT memory_id FROM cluster_syntheses WHERE id=?")
        .param(synthesis.id()).query(String.class).single();
      result.add(memory);
      if(result.size()>=limit) break;
    }
    return result;
  }

  private List<Claim> claims(String synthesisId) {
    return jdbc.sql("SELECT id,text FROM cluster_claims WHERE synthesis_id=? ORDER BY position")
      .param(synthesisId).query((rs,_) -> {
        String id=rs.getString(1);
        List<Source> supports=jdbc.sql("SELECT memory_id,assertion_revision_id FROM cluster_claim_evidence WHERE claim_id=?")
          .param(id).query((e,__) -> new Source(e.getString(1),e.getString(2))).list();
        List<Source> covers=jdbc.sql("SELECT memory_id,assertion_revision_id FROM cluster_claim_evidence WHERE claim_id=? AND covered=1")
          .param(id).query((e,__) -> new Source(e.getString(1),e.getString(2))).list();
        return new Claim(rs.getString(2),supports,covers);
      }).list();
  }

  private boolean eligible(String profileId,Synthesis synthesis,TemporalSnapshot snapshot,Set<String> seen) {
    if(!seen.add(synthesis.id())) return false;
    for(Claim claim:synthesis.claims()) for(Source source:claim.supports())
      if(!sourceVisible(profileId,source,snapshot)) return false;
    List<String[]> pins=jdbc.sql("SELECT child_cluster_id,child_synthesis_id FROM cluster_synthesis_children WHERE synthesis_id=?")
      .param(synthesis.id()).query((rs,_) -> new String[]{rs.getString(1),rs.getString(2)}).list();
    for(String[] pin:pins) {
      Synthesis current=synthesis(profileId,pin[0],snapshot).orElse(null);
      if(current==null || !current.id().equals(pin[1])) return false;
    }
    return true;
  }

  private boolean sourceVisible(String profileId,Source source,TemporalSnapshot snapshot) {
    Instant world=snapshot==null?Instant.now():snapshot.asOf();
    Instant known=snapshot==null?Instant.now():snapshot.knownAsOf();
    long sequence=snapshot==null?Long.MAX_VALUE:snapshot.sequence();
    Integer count=jdbc.sql("""
      SELECT COUNT(*) FROM assertion_revisions ar
      JOIN memory_assertions ma ON ma.id=ar.assertion_id
      JOIN memories m ON m.id=ar.memory_id
      JOIN validity_segments v ON v.revision_id=ar.id
      WHERE ma.profile_id=? AND m.profile_id=? AND m.forgotten=0
      AND ar.memory_id=? AND ar.id=?
      AND v.valid_from<=? AND (v.valid_to IS NULL OR v.valid_to>?)
      AND v.recorded_from<=? AND (v.recorded_to IS NULL OR v.recorded_to>?)
      AND v.from_sequence<=? AND (v.to_sequence IS NULL OR v.to_sequence>?)
      """).params(profileId,profileId,source.memoryId(),source.assertionRevisionId(),
        TemporalSnapshot.timestamp(world),TemporalSnapshot.timestamp(world),
        TemporalSnapshot.timestamp(known),TemporalSnapshot.timestamp(known),sequence,sequence)
      .query(Integer.class).single();
    return count>0;
  }

  @Override public Synthesis publish(String profileId,String clusterId,int expectedRevision,String fingerprint,
      String body,List<Claim> claims,String modelVersion,String promptVersion,String validation,String actor,
      List<float[]> claimEmbeddings) {
    Head head=require(profileId,clusterId,expectedRevision);
    if(!head.state.equals("active")) throw new ClusterConflictException("cluster dissolved");
    if(!fingerprint(profileId,clusterId).equals(fingerprint)) throw new ClusterConflictException("cluster inputs changed during generation");
    if(claims==null || claims.isEmpty()) throw new IllegalArgumentException("claims required");
    if(claimEmbeddings!=null && !claimEmbeddings.isEmpty() && claimEmbeddings.size()!=claims.size())
      throw new IllegalArgumentException("claim embeddings must align with claims");
    Set<String> uniqueMemories=new HashSet<>();
    Set<String> descendants=new HashSet<>();
    walk(clusterId,activeGraph(profileId),new HashSet<>(),descendants);
    for(Claim claim:claims) {
      if(claim.text()==null || claim.text().isBlank() || claim.supports()==null || claim.supports().isEmpty())
        throw new IllegalArgumentException("every claim needs text and source support");
      for(Source source:claim.supports()) {
        if(!sourceVisible(profileId,source,null)) throw new ClusterConflictException("source assertion changed");
        if(!descendants.contains(source.memoryId())) throw new IllegalArgumentException("claim support is outside cluster membership");
        uniqueMemories.add(source.memoryId());
      }
      if(claim.covers()!=null && !claim.supports().containsAll(claim.covers()))
        throw new IllegalArgumentException("coverage must be backed by support");
    }
    if(uniqueMemories.size()<3) throw new IllegalArgumentException("automatic synthesis requires three distinct original memories");
    String id=UUID.randomUUID().toString();
    Set<String> originalIds=new HashSet<>();
    walk(clusterId,activeGraph(profileId),new HashSet<>(),originalIds);
    String profileName=jdbc.sql("SELECT name FROM profiles WHERE id=?").param(profileId)
      .query(String.class).single();
    String inspection="/v1/profiles/"+java.net.URLEncoder.encode(profileName,java.nio.charset.StandardCharsets.UTF_8)
      .replace("+","%20")+"/clusters/"+clusterId;
    String payload="{\"clusterId\":\""+clusterId+"\",\"structureRevision\":"+expectedRevision+
      ",\"synthesisId\":\""+id+"\",\"directMemberCount\":"+members(clusterId,expectedRevision).size()+
      ",\"distinctOriginalCount\":"+originalIds.size()+",\"inspectionRef\":\""+inspection+"\"}";
    Memory generated=memoryStore.store(profileId,Memory.of(MemoryType.CLUSTER,required(body,"body"),
      "cluster:"+clusterId,"cluster:"+clusterId+":"+id,payload)).stored();
    jdbc.sql("INSERT INTO cluster_syntheses(id,cluster_id,memory_id,structure_revision,body,fingerprint,status,recorded_at,recorded_sequence,model_version,prompt_version,validation) VALUES(?,?,?,?,?,?,'published',?,?,?,?,?)")
      .params(id,clusterId,generated.id(),expectedRevision,body,required(fingerprint,"fingerprint"),TemporalSnapshot.timestamp(Instant.now()),sequence(),modelVersion,promptVersion,validation==null?"{}":validation).update();
    int position=0;
    for(Claim claim:claims) {
      String claimId=UUID.randomUUID().toString();
      byte[] embedding=claimEmbeddings==null || claimEmbeddings.isEmpty()?null:
        dev.alvo.pieria.tools.EmbeddingCodec.encode(claimEmbeddings.get(position));
      jdbc.sql("INSERT INTO cluster_claims(id,synthesis_id,position,text,embedding) VALUES(?,?,?,?,?)")
        .params(claimId,id,position++,claim.text(),embedding).update();
      for(Source source:new LinkedHashSet<>(claim.supports())) jdbc.sql("INSERT INTO cluster_claim_evidence(claim_id,memory_id,assertion_revision_id,covered) VALUES(?,?,?,?)")
        .params(claimId,source.memoryId(),source.assertionRevisionId(),claim.covers()!=null && claim.covers().contains(source)?1:0).update();
    }
    for(Member member:members(clusterId,expectedRevision)) if(member.kind().equals("cluster")) {
      Synthesis child=synthesis(profileId,member.id(),null).orElseThrow(() -> new ClusterConflictException("child synthesis unavailable"));
      jdbc.sql("INSERT INTO cluster_synthesis_children(synthesis_id,child_cluster_id,child_synthesis_id) VALUES(?,?,?)")
        .params(id,member.id(),child.id()).update();
    }
    audit(profileId,clusterId,actor,"publish",json.writeValueAsString(Map.of("synthesis",id,
      "fingerprint",fingerprint,"modelVersion",modelVersion,"promptVersion",promptVersion,
      "claimCount",claims.size())));
    jdbc.sql("DELETE FROM cluster_dirty WHERE profile_id=? AND cluster_id=?")
      .params(profileId,clusterId).update();
    // A parent's pinned child synthesis is now stale even when its structure and original
    // assertions did not change. Queue every ancestor, including through shared paths.
    jdbc.sql("""
      INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
      SELECT profile_id,ancestor_id,'child_synthesis',? FROM cluster_dependencies
      WHERE profile_id=? AND descendant_kind='cluster' AND descendant_id=?
      ON CONFLICT(profile_id,cluster_id) DO UPDATE SET
        reason=excluded.reason, recorded_at=excluded.recorded_at
      """).params(TemporalSnapshot.timestamp(Instant.now()),profileId,clusterId).update();
    return synthesis(profileId,clusterId,null).orElseThrow();
  }

  private Run mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Run(rs.getString("id"),rs.getString("profile_id"),rs.getString("state"),
      rs.getString("trigger_name"),rs.getString("checkpoint"),rs.getString("created_at"),rs.getString("updated_at"),
      new TemporalSnapshot(Instant.parse(rs.getString("snapshot_as_of")),
        Instant.parse(rs.getString("snapshot_known_as_of")),rs.getLong("snapshot_sequence"),false),
      rs.getLong("event_after"),rs.getLong("event_through"));
  }

  @Override public Run startRun(String profileId,String trigger) {
    Run unfinished=jdbc.sql("""
      SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs
      WHERE profile_id=? AND state IN ('queued','running','pausing','paused','waiting') LIMIT 1
      """).param(profileId).query((rs,_) -> mapRun(rs)).optional().orElse(null);
    if(unfinished!=null) return unfinished;
    String id=UUID.randomUUID().toString();String now=TemporalSnapshot.timestamp(Instant.now());
    TemporalSnapshot snapshot=memoryStore.temporalSnapshot(null,null);
    long eventAfter=jdbc.sql("SELECT last_event_id FROM cluster_discovery_state WHERE profile_id=?")
      .param(profileId).query(Long.class).optional().orElse(-1L);
    long eventThrough=jdbc.sql("SELECT COALESCE(MAX(id),0) FROM cluster_discovery_changes WHERE profile_id=?")
      .param(profileId).query(Long.class).single();
    jdbc.sql("""
      INSERT INTO cluster_runs(id,profile_id,state,trigger_name,created_at,updated_at,
        snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through)
      VALUES(?,?,'queued',?,?,?,?,?,?,?,?)
      """).params(id,profileId,required(trigger,"trigger"),now,now,
        TemporalSnapshot.timestamp(snapshot.asOf()),TemporalSnapshot.timestamp(snapshot.knownAsOf()),
        snapshot.sequence(),eventAfter,eventThrough).update();
    auditRun(id,null,"start",Map.of("trigger",trigger));
    return run(profileId,id).orElseThrow();
  }

  @Override public Optional<Run> run(String profileId,String runId) {
    return jdbc.sql("SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs WHERE profile_id=? AND id=?")
      .params(profileId,runId).query((rs,_) -> mapRun(rs)).optional();
  }

  @Override public List<Run> runs(String profileId,int limit) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    return jdbc.sql("SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs WHERE profile_id=? ORDER BY created_at DESC LIMIT ?")
      .params(profileId,limit).query((rs,_) -> mapRun(rs)).list();
  }

  @Override public List<Work> work(String profileId,String runId,int limit) {
    if(limit<1 || limit>200) throw new IllegalArgumentException("limit must be 1..200");
    if(run(profileId,runId).isEmpty()) throw new IllegalArgumentException("run not found");
    return jdbc.sql("""
      SELECT id,subject_kind,subject_id,stage,status,attempts,output FROM cluster_work_items
      WHERE run_id=? ORDER BY rowid DESC LIMIT ?
      """).params(runId,limit).query((rs,_) -> new Work(rs.getString(1),rs.getString(2),rs.getString(3),
        rs.getString(4),rs.getString(5),rs.getInt(6),rs.getString(7))).list();
  }

  @Override public List<Map<String,Object>> auditEvents(String profileId,String runId,int limit) {
    if(limit<1 || limit>200) throw new IllegalArgumentException("limit must be 1..200");
    if(run(profileId,runId).isEmpty()) throw new IllegalArgumentException("run not found");
    return jdbc.sql("""
      SELECT id,cluster_id,actor,trigger_name,action,detail,recorded_at
      FROM cluster_audit_events WHERE profile_id=? AND run_id=?
      ORDER BY recorded_at,id LIMIT ?
      """).params(profileId,runId,limit).query((r,n)->{
        Map<String,Object> event=new java.util.LinkedHashMap<>();
        event.put("id",r.getString(1));event.put("clusterId",r.getString(2));
        event.put("actor",r.getString(3));event.put("trigger",r.getString(4));
        event.put("action",r.getString(5));event.put("detail",r.getString(6));
        event.put("recordedAt",r.getString(7));return event;
      }).list();
  }

  @Override public Run transitionRun(String profileId,String runId,String action) {
    Run run=run(profileId,runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
    String next=switch(action) {
      case "pause" -> switch(run.state()) {
        case "queued","running","waiting" -> "paused";
        case "paused" -> "paused";
        default -> throw new ClusterConflictException("run cannot be paused");
      };
      case "resume" -> switch(run.state()) {
        case "paused","waiting" -> "queued";
        case "queued" -> "queued";
        default -> throw new ClusterConflictException("run cannot be resumed");
      };
      case "cancel" -> switch(run.state()) {
        case "queued","running","paused","waiting" -> "cancelled";
        case "cancelled" -> "cancelled";
        default -> throw new ClusterConflictException("run cannot be cancelled");
      };
      default -> throw new IllegalArgumentException("unknown run action");
    };
    jdbc.sql("UPDATE cluster_runs SET state=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?")
      .params(next,TemporalSnapshot.timestamp(Instant.now()),runId).update();
    auditRun(runId,null,"transition",Map.of("from",run.state(),"to",next,"action",action));
    return run(profileId,runId).orElseThrow();
  }

  @Override public Optional<Run> claimRun(String workerId) {
    String now=TemporalSnapshot.timestamp(Instant.now());
    Integer active=jdbc.sql("SELECT COUNT(*) FROM cluster_runs WHERE state='running' AND lease_until>?")
      .param(now).query(Integer.class).single();
    if(active>0) return Optional.empty();
    Run run=jdbc.sql("""
      SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs
      WHERE state='queued' OR (state='running' AND lease_until<?)
      ORDER BY created_at LIMIT 1
      """).param(now).query((rs,_) -> mapRun(rs)).optional().orElse(null);
    if(run==null) return Optional.empty();
    int claimed=jdbc.sql("""
      UPDATE cluster_runs SET state='running',lease_owner=?,lease_until=?,updated_at=?
      WHERE id=? AND (state='queued' OR (state='running' AND lease_until<?))
      """).params(workerId,TemporalSnapshot.timestamp(Instant.now().plusSeconds(120)),now,run.id(),now).update();
    if(claimed!=1) return Optional.empty();
    return run(run.profileId(),run.id());
  }

  @Override public void renewRun(String runId,String workerId) {
    String now=TemporalSnapshot.timestamp(Instant.now());
    int renewed=jdbc.sql("""
      UPDATE cluster_runs SET lease_until=?,updated_at=? WHERE id=? AND state='running'
      AND lease_owner=? AND lease_until>?
      """).params(TemporalSnapshot.timestamp(Instant.now().plusSeconds(120)),now,runId,workerId,now).update();
    if(renewed!=1) throw new ClusterConflictException("cluster run lease lost");
  }

  @Override public boolean canPublishRun(String profileId,String runId,String workerId) {
    Integer count=jdbc.sql("""
      SELECT COUNT(*) FROM cluster_runs WHERE id=? AND profile_id=? AND state='running'
      AND lease_owner=? AND lease_until>?
      """).params(runId,profileId,workerId,TemporalSnapshot.timestamp(Instant.now()))
      .query(Integer.class).single();
    return count>0;
  }

  @Override public List<Topic> topics(String profileId,String after,int limit,TemporalSnapshot snapshot) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    List<String> keys=jdbc.sql("""
      SELECT topic_key FROM memories WHERE profile_id=? AND topic_key>? AND type<>'cluster'
      AND forgotten=0 GROUP BY topic_key HAVING COUNT(DISTINCT id)>=3 ORDER BY topic_key LIMIT ?
      """).params(profileId,after==null?"":after,limit).query(String.class).list();
    List<Topic> result=new ArrayList<>();
    for(String key:keys) {
      List<Member> members=jdbc.sql("""
        SELECT m.id FROM memories m WHERE m.profile_id=? AND m.topic_key=? AND m.type<>'cluster'
        AND m.forgotten=0 AND EXISTS (
          SELECT 1 FROM assertion_revisions ar JOIN validity_segments v ON v.revision_id=ar.id
          WHERE ar.memory_id=m.id AND v.valid_from<=? AND (v.valid_to IS NULL OR v.valid_to>?)
          AND v.recorded_from<=? AND (v.recorded_to IS NULL OR v.recorded_to>?)
          AND v.from_sequence<=? AND (v.to_sequence IS NULL OR v.to_sequence>?))
        ORDER BY m.id LIMIT 21
        """).params(profileId,key,TemporalSnapshot.timestamp(snapshot.asOf()),
          TemporalSnapshot.timestamp(snapshot.asOf()),TemporalSnapshot.timestamp(snapshot.knownAsOf()),
          TemporalSnapshot.timestamp(snapshot.knownAsOf()),snapshot.sequence(),snapshot.sequence())
        .query((rs,_) -> new Member("memory",rs.getString(1))).list();
      result.add(new Topic(key,members));
    }
    return result;
  }

  @Override public List<Topic> broaderTopics(String profileId,String after,int limit) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    // Only a distinctive explicit namespace can nominate a broader subject.
    // Coherence validation remains the deciding gate before generation.
    List<String> prefixes=jdbc.sql("""
      SELECT substr(substr(s.scope,11),1,instr(substr(s.scope,11),':')-1) AS prefix
      FROM clusters c JOIN cluster_structure_revisions s
      ON s.cluster_id=c.id AND s.revision=c.current_revision
      WHERE c.profile_id=? AND s.state='active' AND s.scope LIKE 'topic-key:%'
      AND instr(substr(s.scope,11),':')>1
      GROUP BY prefix HAVING COUNT(DISTINCT c.id)>=2 AND prefix>?
      ORDER BY prefix LIMIT ?
      """).params(profileId,after==null?"":after,limit).query(String.class).list();
    List<Topic> result=new ArrayList<>();
    for(String prefix:prefixes) {
      if(prefix.length()<4 || Set.of("code","trace","task","project","general").contains(prefix.toLowerCase())) continue;
      List<Member> members=jdbc.sql("""
        SELECT c.id FROM clusters c JOIN cluster_structure_revisions s
        ON s.cluster_id=c.id AND s.revision=c.current_revision
        WHERE c.profile_id=? AND s.state='active' AND s.scope LIKE ?
        ORDER BY s.scope,c.id LIMIT 21
        """).params(profileId,"topic-key:"+prefix+":%")
        .query((r,n)->new Member("cluster",r.getString(1))).list();
      result.add(new Topic("broader:"+prefix,prefix,"broader:"+prefix,members));
    }
    return result;
  }

  @Override public List<Topic> entityTopics(String profileId,String after,int limit,TemporalSnapshot snapshot) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    List<String> entities=jdbc.sql("""
      SELECT e.id FROM entities e JOIN edges x ON (x.source_entity_id=e.id OR x.target_entity_id=e.id)
      JOIN memories m ON m.id=x.memory_id AND m.profile_id=e.profile_id
      WHERE e.profile_id=? AND e.id>? AND m.forgotten=0 AND m.type<>'cluster'
      GROUP BY e.id HAVING COUNT(DISTINCT m.id)>=3 ORDER BY e.id LIMIT ?
      """).params(profileId,after==null?"":after,limit).query(String.class).list();
    List<Topic> result=new ArrayList<>();
    for(String id:entities) {
      String title=jdbc.sql("SELECT name FROM entities WHERE id=? AND profile_id=?")
        .params(id,profileId).query(String.class).single();
      List<Member> members=jdbc.sql("""
        SELECT DISTINCT m.id FROM memories m JOIN edges x ON x.memory_id=m.id
        WHERE m.profile_id=? AND m.forgotten=0 AND m.type<>'cluster'
        AND (x.source_entity_id=? OR x.target_entity_id=?)
        AND EXISTS (SELECT 1 FROM assertion_revisions ar JOIN validity_segments v ON v.revision_id=ar.id
          WHERE ar.memory_id=m.id AND v.valid_from<=? AND (v.valid_to IS NULL OR v.valid_to>?)
          AND v.recorded_from<=? AND (v.recorded_to IS NULL OR v.recorded_to>?)
          AND v.from_sequence<=? AND (v.to_sequence IS NULL OR v.to_sequence>?))
        ORDER BY m.id LIMIT 21
        """).params(profileId,id,id,TemporalSnapshot.timestamp(snapshot.asOf()),
          TemporalSnapshot.timestamp(snapshot.asOf()),TemporalSnapshot.timestamp(snapshot.knownAsOf()),
          TemporalSnapshot.timestamp(snapshot.knownAsOf()),snapshot.sequence(),snapshot.sequence())
        .query((r,n)->new Member("memory",r.getString(1))).list();
      result.add(new Topic("entity:"+id,title,"entity:"+id,members));
    }
    return result;
  }

  @Override public List<String> discoverySeeds(String profileId,String after,int limit,
      TemporalSnapshot snapshot,long eventAfter,long eventThrough) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    String changed=eventAfter<0?"":"""
      AND EXISTS (SELECT 1 FROM cluster_discovery_changes dc
        WHERE dc.profile_id=m.profile_id AND dc.memory_id=m.id
        AND dc.id>? AND dc.id<=?)
      """;
    List<Object> params=new ArrayList<>(List.of(profileId,after==null?"":after,
      TemporalSnapshot.timestamp(snapshot.asOf()),TemporalSnapshot.timestamp(snapshot.asOf()),
      TemporalSnapshot.timestamp(snapshot.knownAsOf()),TemporalSnapshot.timestamp(snapshot.knownAsOf()),
      snapshot.sequence(),snapshot.sequence()));
    if(eventAfter>=0) {params.add(eventAfter);params.add(eventThrough);}
    params.add(limit);
    return jdbc.sql("""
      SELECT m.id FROM memories m WHERE m.profile_id=? AND m.id>? AND m.forgotten=0
      AND m.type NOT IN ('cluster','task')
      AND EXISTS (SELECT 1 FROM assertion_revisions ar JOIN validity_segments v ON v.revision_id=ar.id
        WHERE ar.memory_id=m.id AND v.valid_from<=? AND (v.valid_to IS NULL OR v.valid_to>?)
        AND v.recorded_from<=? AND (v.recorded_to IS NULL OR v.recorded_to>?)
        AND v.from_sequence<=? AND (v.to_sequence IS NULL OR v.to_sequence>?))
      """+changed+" ORDER BY m.id LIMIT ?")
      .params(params)
      .query(String.class).list();
  }

  @Override public Optional<Cluster> overlappingDiscovery(String profileId,List<Member> proposed) {
    if(proposed==null || proposed.isEmpty()) return Optional.empty();
    Set<Member> incoming=new HashSet<>(proposed);
    Cluster best=null;double overlap=.74;
    String placeholders=String.join(",",java.util.Collections.nCopies(proposed.size(),"?"));
    List<Object> params=new ArrayList<>();params.add(profileId);
    proposed.forEach(member->params.add(member.id()));
    for(String id:jdbc.sql("""
      SELECT c.id FROM clusters c JOIN cluster_structure_revisions s
      ON s.cluster_id=c.id AND s.revision=c.current_revision
      WHERE c.profile_id=? AND s.state='active' AND EXISTS (
        SELECT 1 FROM cluster_memberships x WHERE x.cluster_id=c.id AND x.revision=c.current_revision
        AND x.child_kind='memory' AND x.child_id IN (
      """+placeholders+")) ORDER BY c.id")
      .params(params).query(String.class).list()) {
      Head head=head(profileId,id,null).orElseThrow();
      Set<Member> existing=new HashSet<>(members(id,head.revision()));
      int shared=0;for(Member member:incoming) if(existing.contains(member)) shared++;
      double score=(double)shared/(incoming.size()+existing.size()-shared);
      if(score>overlap) {best=detail(profileId,id,null).orElseThrow().cluster();overlap=score;}
    }
    return Optional.ofNullable(best);
  }

  @Override public boolean protectedGroup(String profileId,List<Member> members) {
    String fingerprint=members.stream().sorted(Comparator.comparing(Member::kind).thenComparing(Member::id)).toList().toString();
    Integer count=jdbc.sql("SELECT COUNT(*) FROM cluster_dissolved_groups WHERE profile_id=? AND fingerprint=?")
      .params(profileId,fingerprint).query(Integer.class).single();
    return count>0;
  }

  @Override public void checkpoint(String runId,String topicKey,String status,String detail) {
    String id=UUID.randomUUID().toString();
    jdbc.sql("""
      INSERT INTO cluster_work_items(id,run_id,subject_kind,subject_id,stage,status,output)
      VALUES(?,?,'topic',?,'generation',?,?)
      ON CONFLICT(run_id,subject_kind,subject_id,stage)
      DO UPDATE SET status=excluded.status,output=excluded.output,attempts=cluster_work_items.attempts+1
      """).params(id,runId,topicKey,status,detail).update();
    jdbc.sql("UPDATE cluster_runs SET checkpoint=?,updated_at=? WHERE id=? AND state='running'")
      .params(topicKey,TemporalSnapshot.timestamp(Instant.now()),runId).update();
    auditRun(runId,null,"work_item",Map.of("subject",topicKey,"status",status,"detail",detail));
  }

  @Override public void finishSlice(String runId,boolean exhausted) {
    Integer failures=jdbc.sql("SELECT COUNT(*) FROM cluster_work_items WHERE run_id=? AND status='failed'")
      .param(runId).query(Integer.class).single();
    Integer pending=jdbc.sql("SELECT COUNT(*) FROM cluster_work_items WHERE run_id=? AND stage='regenerate' AND status='pending'")
      .param(runId).query(Integer.class).single();
    String state=exhausted && pending==0?(failures>0?"completed_with_errors":"completed"):"waiting";
    jdbc.sql("UPDATE cluster_runs SET state=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND state='running'")
      .params(state,TemporalSnapshot.timestamp(Instant.now()),runId).update();
    if(state.equals("completed")) jdbc.sql("""
      INSERT INTO cluster_discovery_state(profile_id,last_event_id)
      SELECT profile_id,event_through FROM cluster_runs
      WHERE id=? AND trigger_name<>'regenerate'
      ON CONFLICT(profile_id) DO UPDATE SET last_event_id=MAX(
        cluster_discovery_state.last_event_id,excluded.last_event_id)
      """).param(runId).update();
  }

  @Override public void enqueueRegeneration(String runId,String clusterId,int expectedRevision) {
    jdbc.sql("""
      INSERT INTO cluster_work_items(id,run_id,subject_kind,subject_id,stage,status,input_fingerprint)
      VALUES(?,?,'cluster',?,'regenerate','pending',?)
      ON CONFLICT(run_id,subject_kind,subject_id,stage)
      DO UPDATE SET status='pending',input_fingerprint=excluded.input_fingerprint
      """).params(UUID.randomUUID().toString(),runId,clusterId,Integer.toString(expectedRevision)).update();
  }

  @Override public void enqueueDirtyRegenerations(String runId,String profileId) {
    Map<String,List<Member>> graph=activeGraph(profileId);
    List<String> dirty=jdbc.sql("SELECT cluster_id FROM cluster_dirty WHERE profile_id=?")
      .param(profileId).query(String.class).list().stream().filter(graph::containsKey).toList();
    List<String> ordered=new ArrayList<>(dirty);
    ordered.sort(Comparator.comparingInt((String id)->walk(id,graph,new HashSet<>(),new HashSet<>()))
      .thenComparing(id->id));
    for(String id:ordered) {
      int revision=head(profileId,id,null).orElseThrow().revision();
      enqueueRegeneration(runId,id,revision);
    }
  }

  @Override public List<Regeneration> pendingRegenerations(String runId,int limit) {
    return jdbc.sql("""
      SELECT id,subject_id,input_fingerprint,attempts FROM cluster_work_items
      WHERE run_id=? AND stage='regenerate' AND status='pending' ORDER BY rowid LIMIT ?
      """).params(runId,limit).query((rs,_) -> new Regeneration(rs.getString(1),rs.getString(2),
        Integer.parseInt(rs.getString(3)),rs.getInt(4))).list();
  }

  @Override public void finishRegeneration(String itemId,String status,String detail) {
    String[] item=jdbc.sql("SELECT run_id,subject_id FROM cluster_work_items WHERE id=?")
      .param(itemId).query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
    jdbc.sql("UPDATE cluster_work_items SET status=?,output=?,attempts=attempts+1 WHERE id=?")
      .params(status.equals("deferred")?"pending":status,detail,itemId).update();
    if(status.equals("published")) jdbc.sql("DELETE FROM cluster_dirty WHERE cluster_id=? AND profile_id=(SELECT profile_id FROM cluster_runs WHERE id=?)")
      .params(item[1],item[0]).update();
    auditRun(item[0],item[1],"regeneration",Map.of("status",status,"detail",detail));
  }

  @Override public Optional<Cluster> byScope(String profileId,String scope) {
    String id=jdbc.sql("""
      SELECT c.id FROM clusters c JOIN cluster_structure_revisions s
      ON s.cluster_id=c.id AND s.revision=c.current_revision
      WHERE c.profile_id=? AND s.scope=? AND s.state='active' ORDER BY c.created_at LIMIT 1
      """).params(profileId,scope).query(String.class).optional().orElse(null);
    return id==null?Optional.empty():detail(profileId,id,null).map(Detail::cluster);
  }

  @Override public boolean excluded(String profileId,String clusterId,Member member) {
    Integer count=jdbc.sql("""
      SELECT COUNT(*) FROM cluster_overrides WHERE profile_id=? AND cluster_id=?
      AND child_kind=? AND child_id=? AND decision='excluded'
      """).params(profileId,clusterId,member.kind(),member.id()).query(Integer.class).single();
    return count>0;
  }

  @Override public Schedule schedule(String profileId) {
    return jdbc.sql("SELECT enabled,local_time,timezone,last_local_date FROM cluster_schedules WHERE profile_id=?")
      .param(profileId).query((rs,_) -> new Schedule(rs.getInt(1)!=0,rs.getString(2),rs.getString(3),rs.getString(4)))
      .optional().orElse(new Schedule(false,"02:00",java.time.ZoneId.systemDefault().getId(),null));
  }

  @Override public Schedule setSchedule(String profileId,boolean enabled,String localTime,String timezone) {
    java.time.LocalTime parsed=java.time.LocalTime.parse(localTime==null?"02:00":localTime);
    java.time.ZoneId zone=java.time.ZoneId.of(timezone==null?java.time.ZoneId.systemDefault().getId():timezone);
    String time=parsed.toString();
    jdbc.sql("""
      INSERT INTO cluster_schedules(profile_id,enabled,local_time,timezone)
      VALUES(?,?,?,?) ON CONFLICT(profile_id)
      DO UPDATE SET enabled=excluded.enabled,local_time=excluded.local_time,timezone=excluded.timezone
      """).params(profileId,enabled?1:0,time,zone.getId()).update();
    return schedule(profileId);
  }

  @Override public List<Run> activateDueSchedules(Instant now) {
    List<String> profiles=jdbc.sql("SELECT profile_id FROM cluster_schedules WHERE enabled=1")
      .query(String.class).list();
    List<Run> activated=new ArrayList<>();
    for(String profileId:profiles) {
      Schedule schedule=schedule(profileId);
      var zone=java.time.ZoneId.of(schedule.timezone());
      var local=now.atZone(zone);
      var due=local.toLocalDate().atTime(java.time.LocalTime.parse(schedule.localTime())).atZone(zone);
      long seconds=java.time.Duration.between(due,local).toSeconds();
      if(seconds<0 || seconds>=120) continue; // Missed occurrences are never replayed.
      String date=local.toLocalDate().toString();
      int claimed=jdbc.sql("""
        UPDATE cluster_schedules SET last_local_date=? WHERE profile_id=?
        AND (last_local_date IS NULL OR last_local_date<>?)
        """).params(date,profileId,date).update();
      if(claimed==0) continue;
      Run unfinished=jdbc.sql("""
        SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs
        WHERE profile_id=? AND state IN ('queued','running','pausing','paused','waiting') LIMIT 1
        """).param(profileId).query((rs,_) -> mapRun(rs)).optional().orElse(null);
      if(unfinished==null) activated.add(startRun(profileId,"schedule:"+date));
      else if(unfinished.state().equals("waiting")) activated.add(transitionRun(profileId,unfinished.id(),"resume"));
    }
    return activated;
  }

  @Override public List<Run> activateDueBoundaries(Instant now) {
    String cutoff=TemporalSnapshot.timestamp(now);
    List<String> affected=jdbc.sql("""
      SELECT DISTINCT b.profile_id FROM cluster_temporal_boundaries b
      JOIN cluster_dependencies d ON d.profile_id=b.profile_id
        AND d.descendant_kind='memory' AND d.descendant_id=b.memory_id
      WHERE b.boundary_at<=?
      """).param(cutoff).query(String.class).list();
    jdbc.sql("""
      INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
      SELECT DISTINCT d.profile_id,d.ancestor_id,'validity_boundary',?
      FROM cluster_temporal_boundaries b JOIN cluster_dependencies d
        ON d.profile_id=b.profile_id AND d.descendant_kind='memory'
        AND d.descendant_id=b.memory_id WHERE b.boundary_at<=?
      ON CONFLICT(profile_id,cluster_id) DO UPDATE SET
        reason=excluded.reason,recorded_at=excluded.recorded_at
      """).params(cutoff,cutoff).update();
    jdbc.sql("DELETE FROM cluster_temporal_boundaries WHERE boundary_at<=?")
      .param(cutoff).update();
    List<Run> activated=new ArrayList<>();
    for(String profileId:affected) activated.add(startRun(profileId,"validity_boundary"));
    return activated;
  }

  @Override public Map<String,Object> exportProfile(String profileId) {
    Map<String,Object> archive=new java.util.LinkedHashMap<>();
    List<Map<String,Object>> containers=new ArrayList<>();
    for(String id:jdbc.sql("SELECT id FROM clusters WHERE profile_id=? ORDER BY created_at,id")
        .param(profileId).query(String.class).list()) {
      Map<String,Object> container=new java.util.LinkedHashMap<>();
      container.put("structure",detail(profileId,id,null).orElseThrow());
      List<Map<String,Object>> generated=new ArrayList<>();
      for(String synthesisId:jdbc.sql("SELECT id FROM cluster_syntheses WHERE cluster_id=? ORDER BY recorded_sequence,id")
          .param(id).query(String.class).list()) {
        var row=jdbc.sql("SELECT structure_revision,body,fingerprint,status,recorded_at,model_version,prompt_version,validation FROM cluster_syntheses WHERE id=?")
          .param(synthesisId).query((r,n)->new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getString(4),
            r.getString(5),r.getString(6),r.getString(7),r.getString(8)}).single();
        boolean visible=synthesis(profileId,id,null).map(current->current.id().equals(synthesisId)).orElse(false);
        Map<String,Object> item=new java.util.LinkedHashMap<>();
        item.put("id",synthesisId);item.put("structureRevision",row[0]);
        item.put("fingerprint",row[2]);item.put("status",row[3]);item.put("recordedAt",row[4]);
        item.put("modelVersion",row[5]);item.put("promptVersion",row[6]);item.put("validation",row[7]);
        item.put("childPins",jdbc.sql("SELECT child_cluster_id,child_synthesis_id FROM cluster_synthesis_children WHERE synthesis_id=? ORDER BY child_cluster_id")
          .param(synthesisId).query((r,n)->Map.of("clusterId",r.getString(1),"synthesisId",r.getString(2))).list());
        if(visible) {item.put("body",row[1]);item.put("claims",claims(synthesisId));}
        else item.put("redactionReason","source_or_revision_unavailable");
        generated.add(item);
      }
      container.put("syntheses",generated);containers.add(container);
    }
    archive.put("clusters",containers);
    List<Run> archivedRuns=jdbc.sql("SELECT id,profile_id,state,trigger_name,checkpoint,created_at,updated_at,snapshot_as_of,snapshot_known_as_of,snapshot_sequence,event_after,event_through FROM cluster_runs WHERE profile_id=? ORDER BY created_at,id")
      .param(profileId).query((r,n)->mapRun(r)).list();
    archive.put("runs",archivedRuns);
    List<Map<String,Object>> work=new ArrayList<>();
    for(Run run:archivedRuns) work.add(Map.of("runId",run.id(),"items",
      jdbc.sql("SELECT id,subject_kind,subject_id,stage,status,attempts,output FROM cluster_work_items WHERE run_id=? ORDER BY rowid")
        .param(run.id()).query((r,n)->new Work(r.getString(1),r.getString(2),r.getString(3),
          r.getString(4),r.getString(5),r.getInt(6),r.getString(7))).list()));
    archive.put("work",work);
    archive.put("overrides",jdbc.sql("SELECT cluster_id,child_kind,child_id,decision,recorded_at FROM cluster_overrides WHERE profile_id=? ORDER BY cluster_id,child_kind,child_id")
      .param(profileId).query((r,n)->Map.of("clusterId",r.getString(1),"childKind",r.getString(2),"childId",r.getString(3),
        "decision",r.getString(4),"recordedAt",r.getString(5))).list());
    archive.put("dependencies",jdbc.sql("SELECT ancestor_id,descendant_kind,descendant_id FROM cluster_dependencies WHERE profile_id=? ORDER BY ancestor_id,descendant_kind,descendant_id")
      .param(profileId).query((r,n)->Map.of("ancestorId",r.getString(1),"kind",r.getString(2),"id",r.getString(3))).list());
    archive.put("dirty",jdbc.sql("SELECT cluster_id,reason,recorded_at FROM cluster_dirty WHERE profile_id=? ORDER BY cluster_id")
      .param(profileId).query((r,n)->Map.of("clusterId",r.getString(1),"reason",r.getString(2),"recordedAt",r.getString(3))).list());
    archive.put("dissolvedGroups",jdbc.sql("SELECT fingerprint,cluster_id,recorded_at FROM cluster_dissolved_groups WHERE profile_id=? ORDER BY recorded_at")
      .param(profileId).query((r,n)->Map.of("fingerprint",r.getString(1),"clusterId",r.getString(2),"recordedAt",r.getString(3))).list());
    archive.put("audit",jdbc.sql("SELECT id,cluster_id,run_id,actor,trigger_name,action,detail,recorded_at FROM cluster_audit_events WHERE profile_id=? ORDER BY recorded_at,id")
      .param(profileId).query((r,n)->{
        Map<String,Object> event=new java.util.LinkedHashMap<>();
        event.put("id",r.getString(1));event.put("clusterId",r.getString(2));event.put("runId",r.getString(3));
        event.put("actor",r.getString(4));event.put("trigger",r.getString(5));event.put("action",r.getString(6));
        event.put("detail",r.getString(7));event.put("recordedAt",r.getString(8));return event;
      }).list());
    archive.put("schedule",schedule(profileId));
    archive.put("discoveryCursor",jdbc.sql("SELECT last_event_id FROM cluster_discovery_state WHERE profile_id=?")
      .param(profileId).query(Long.class).optional().orElse(null));
    archive.put("discoveryChanges",jdbc.sql("""
      SELECT dc.id,dc.kind,dc.memory_id,m.forgotten FROM cluster_discovery_changes dc
      JOIN memories m ON m.id=dc.memory_id WHERE dc.profile_id=? ORDER BY dc.id
      """).param(profileId).query((r,n)->{
        Map<String,Object> event=new java.util.LinkedHashMap<>();
        event.put("id",r.getLong(1));event.put("kind",r.getString(2));
        if(r.getInt(4)==0) event.put("memoryId",r.getString(3));
        else event.put("redactionReason","forgotten_source");
        return event;
      }).list());
    archive.put("temporalBoundaries",jdbc.sql("""
      SELECT b.memory_id,b.boundary_at,m.forgotten FROM cluster_temporal_boundaries b
      JOIN memories m ON m.id=b.memory_id WHERE b.profile_id=? ORDER BY b.boundary_at,b.memory_id
      """).param(profileId).query((r,n)->{
        Map<String,Object> boundary=new java.util.LinkedHashMap<>();
        boundary.put("at",r.getString(2));
        if(r.getInt(3)==0) boundary.put("memoryId",r.getString(1));
        else boundary.put("redactionReason","forgotten_source");
        return boundary;
      }).list());
    return archive;
  }

  @Override public void deleteProfile(String profileId) {
    jdbc.sql("DELETE FROM cluster_claim_evidence WHERE claim_id IN (SELECT cc.id FROM cluster_claims cc JOIN cluster_syntheses s ON s.id=cc.synthesis_id JOIN clusters c ON c.id=s.cluster_id WHERE c.profile_id=?)").param(profileId).update();
    jdbc.sql("DELETE FROM cluster_claims WHERE synthesis_id IN (SELECT s.id FROM cluster_syntheses s JOIN clusters c ON c.id=s.cluster_id WHERE c.profile_id=?)").param(profileId).update();
    jdbc.sql("DELETE FROM cluster_synthesis_children WHERE synthesis_id IN (SELECT s.id FROM cluster_syntheses s JOIN clusters c ON c.id=s.cluster_id WHERE c.profile_id=?)").param(profileId).update();
    jdbc.sql("DELETE FROM cluster_syntheses WHERE cluster_id IN (SELECT id FROM clusters WHERE profile_id=?)").param(profileId).update();
    for(String table:List.of("cluster_audit_events","cluster_work_items","cluster_runs","cluster_dissolved_groups","cluster_overrides","cluster_schedules","cluster_dependencies","cluster_dirty","cluster_discovery_changes","cluster_discovery_state","cluster_temporal_boundaries"))
      jdbc.sql("DELETE FROM "+table+" WHERE "+(table.equals("cluster_work_items")?"run_id IN (SELECT id FROM cluster_runs WHERE profile_id=?)":"profile_id=?"))
        .param(profileId).update();
    jdbc.sql("DELETE FROM cluster_memberships WHERE cluster_id IN (SELECT id FROM clusters WHERE profile_id=?)").param(profileId).update();
    jdbc.sql("DELETE FROM cluster_structure_revisions WHERE cluster_id IN (SELECT id FROM clusters WHERE profile_id=?)").param(profileId).update();
    jdbc.sql("DELETE FROM clusters WHERE profile_id=?").param(profileId).update();
  }
}
