package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.wiki.WikiRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static dev.alvo.pieria.wiki.WikiData.*;

/** Transaction-bound SQLite wiki implementation, reached only through MemoryStore. */
final class SqliteWikiStore implements WikiRepository {
  private final JdbcClient db;
  private final MemoryStore memories;
  private TemporalSnapshot requestSnapshot;
  private final Map<String,Optional<Source>> sourceCache = new HashMap<>();
  private final Map<String,Boolean> distinctiveEntities = new HashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  SqliteWikiStore(JdbcClient db, MemoryStore memories) { this.db=db; this.memories=memories; }
  private static Instant instant(String s) { return s==null ? null : Instant.parse(s); }
  private static String time(Instant t) { return TemporalSnapshot.timestamp(t); }
  private long count(String sql, Object... args) { return db.sql(sql).params(args).query(Long.class).single(); }
  @Override public boolean exists(String p) { return count("SELECT COUNT(*) FROM profiles WHERE id=?",p)>0; }
  @Override public TemporalSnapshot snapshot() { if(requestSnapshot==null) requestSnapshot=memories.temporalSnapshot(null,null); return requestSnapshot; }
  @Override public State state(String p) {
    return db.sql("SELECT * FROM wiki_state WHERE profile_id=?").param(p).query((r,n)->new State(r.getString("cursor"),
      r.getLong("checkpoint"),r.getBoolean("initialized"),r.getLong("revision"),r.getString("policy"),instant(r.getString("next_boundary")),r.getLong("target_sequence"),
      r.getString("scan_snapshot")==null?null:json.readValue(r.getString("scan_snapshot"),TemporalSnapshot.class)))
      .optional().orElse(new State("",0,false,0,"",null,0,null));
  }
  @Override public long watermark(String p) { return count("SELECT COALESCE(MAX(sequence),0) FROM wiki_changes WHERE profile_id=?",p); }
  @Override public List<Source> sources(String p, TemporalSnapshot s, String after, int limit) {
    return db.sql("SELECT id FROM memories m WHERE profile_id=? AND id>? AND m.type<>'cluster' AND " + SqliteTemporalStore.visible("m",s)
        + " AND COALESCE(topic_key,'') NOT LIKE 'code:summary:%' ORDER BY id LIMIT ?")
      .params(p,after,limit).query(String.class).list().stream().map(id->source(p,id,s).orElseThrow()).toList();
  }
  @Override public Optional<Source> source(String p, String id, TemporalSnapshot s) {
    return sourceCache.computeIfAbsent(p+":"+id+":"+s, ignored->loadSource(p,id,s));
  }
  private Optional<Source> loadSource(String p, String id, TemporalSnapshot s) {
    if (count("SELECT COUNT(*) FROM memories m WHERE profile_id=? AND id=? AND m.type<>'cluster' AND "+SqliteTemporalStore.visible("m",s)
        +" AND COALESCE(topic_key,'') NOT LIKE 'code:summary:%'",p,id)==0) return Optional.empty();
    return memories.atSnapshot(s,()->memories.findMemoryById(id)).map(m-> {
      List<Anchor> anchors=db.sql("SELECT DISTINCT e.id,e.name,e.type FROM entities e JOIN edges x ON (e.id=x.source_entity_id OR e.id=x.target_entity_id)"
          +" WHERE x.profile_id=? AND x.memory_id=? AND x.recorded_sequence<=? AND x.recorded_at<=? ORDER BY e.id")
        .params(p,id,s.sequence(),time(s.knownAsOf())).query((r,n)->new Anchor(r.getString(1),r.getString(2),r.getString(3))).list();
      return new Source(id,m.type().wire(),m.content(),m.topicKey(),m.payload(),memories.selectedValidity(id,s),anchors,
        memories.embeddingsFor(p,List.of(id)).get(id));
    });
  }
  @Override public Instant nextBoundary(String p, Instant now) {
    // Includes scheduled non-selected replacements. A conservative profile deadline is safe;
    // deterministic re-selection retains unrelated fingerprints without synthesis.
    return db.sql("SELECT MIN(boundary) FROM (SELECT valid_from AS boundary FROM validity_segments WHERE profile_id=? AND to_sequence IS NULL UNION ALL "
        +"SELECT valid_to FROM validity_segments WHERE profile_id=? AND to_sequence IS NULL) WHERE boundary>?")
      .params(p,p,time(now)).query(String.class).optional().map(Instant::parse).orElse(null);
  }
  @Override public List<Change> changes(String p,long after,int limit) {
    return db.sql("SELECT sequence,memory_id,kind FROM wiki_changes WHERE profile_id=? AND sequence>? ORDER BY sequence LIMIT ?")
      .params(p,after,limit).query((r,n)->new Change(r.getLong(1),r.getString(2),r.getString(3))).list();
  }
  @Override public List<String> factPeers(String p,String id) {
    return db.sql("SELECT DISTINCT r.memory_id FROM memory_assertions a JOIN assertion_revisions r ON r.assertion_id=a.id WHERE a.profile_id=? AND a.fact_group IN "
        +"(SELECT a2.fact_group FROM memory_assertions a2 JOIN assertion_revisions r2 ON r2.assertion_id=a2.id WHERE a2.profile_id=? AND r2.memory_id=?)")
      .params(p,p,id).query(String.class).list();
  }
  @Override public List<String> candidateMemories(String p,Source source,TemporalSnapshot s,int limit) {
    LinkedHashSet<String> ids=new LinkedHashSet<>();
    String match=dev.alvo.pieria.wiki.WikiText.match(source.content());
    if (!match.isBlank()) memories.atSnapshot(s,()->memories.searchMemoriesFts(p,match,limit)).forEach(m->ids.add(m.id()));
    // A full profile vector scan per keyed source dominates bootstrap on large code profiles.
    // Key/entity anchors and FTS already provide bounded candidates for cosine admission;
    // reserve the expensive profile-wide scan for evidence without either anchor.
    if (source.embedding()!=null && (source.topicKey()==null || source.topicKey().isBlank()) && source.entities().isEmpty())
      memories.atSnapshot(s,()->memories.vectorSearch(p,source.embedding(),limit)).forEach(m->ids.add(m.id()));
    return List.copyOf(ids);
  }
  @Override public boolean distinctiveEntity(String p,String entity,TemporalSnapshot s) {
    return distinctiveEntities.computeIfAbsent(p+":"+entity+":"+s, ignored -> {
      long eligible=count("SELECT COUNT(*) FROM memories m WHERE profile_id=? AND "+SqliteTemporalStore.visible("m",s)
        +" AND COALESCE(topic_key,'') NOT LIKE 'code:summary:%'",p);
      long frequency=count("SELECT COUNT(DISTINCT m.id) FROM memories m JOIN edges e ON e.profile_id=m.profile_id AND e.memory_id=m.id "
        +"WHERE m.profile_id=? AND (e.source_entity_id=? OR e.target_entity_id=?) AND e.recorded_sequence<=? AND e.recorded_at<=? AND "
        +SqliteTemporalStore.visible("m",s)+" AND COALESCE(m.topic_key,'') NOT LIKE 'code:summary:%'",p,entity,entity,s.sequence(),time(s.knownAsOf()));
      return frequency<=Math.max(10,eligible*.10);
    });
  }
  private Page mapPage(ResultSet r,int n) throws SQLException {
    return new Page(r.getString("id"),r.getString("kind"),r.getString("title"),r.getString("anchor"),r.getString("parent_id"),
      r.getString("lifecycle"),r.getLong("revision"),r.getLong("scope_revision"),r.getBoolean("suppressed"),r.getString("current_version"),
      r.getLong("checked_sequence"),instant(r.getString("next_boundary")),r.getString("signature"),instant(r.getString("created_at")));
  }
  @Override public List<Page> pages(String p,String after,int limit) {
    return db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND id>? ORDER BY id LIMIT ?").params(p,after,limit).query(this::mapPage).list();
  }
  @Override public List<Page> directory(String p,String parent,String after,int limit) {
    Page cursor=after.isEmpty()?null:page(p,after).orElseThrow(()->new dev.alvo.pieria.wiki.WikiException(409,"cursor_expired"));
    if(cursor!=null && !Objects.equals(parent,cursor.parentId())) throw new dev.alvo.pieria.wiki.WikiException(409,"cursor_expired");
    String where=parent==null?"parent_id IS NULL":"parent_id=?";
    List<Object> params=new ArrayList<>();params.add(p);if(parent!=null)params.add(parent);
    if(cursor!=null) {where+=" AND (lower(title),id)>(?,?)";params.add(cursor.title().toLowerCase(Locale.ROOT));params.add(after);}
    params.add(limit);
    return db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND suppressed=0 AND lifecycle='active' AND "+where+" ORDER BY lower(title),id LIMIT ?")
      .params(params).query(this::mapPage).list();
  }
  @Override public int evidenceCount(String p,String page,TemporalSnapshot s) {
    return (int)count("SELECT COUNT(*) FROM wiki_memberships x JOIN memories m ON m.profile_id=x.profile_id AND m.id=x.memory_id "
      +"WHERE x.profile_id=? AND x.page_id=? AND "+SqliteTemporalStore.visible("m",s)+" AND COALESCE(m.topic_key,'') NOT LIKE 'code:summary:%'",p,page);
  }
  @Override public Optional<Page> page(String p,String id) {
    return db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND id=?").params(p,id).query(this::mapPage).optional();
  }
  @Override public Optional<Page> anchor(String p,String anchor) {
    return db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND anchor=?").params(p,anchor).query(this::mapPage).optional();
  }
  @Override public Page ensurePage(String p,String kind,String title,String anchor,String parent) {
    var old=anchor(p,anchor);
    if(old.isPresent()) return page(p,canonical(p,old.get().id())).orElseThrow();
    String id=UUID.randomUUID().toString();
    db.sql("INSERT INTO wiki_pages(profile_id,id,kind,title,anchor,parent_id,created_at) VALUES(?,?,?,?,?,?,?)")
      .params(p,id,kind,title,anchor,parent,time(Instant.now())).update();
    if(parent!=null) db.sql("INSERT OR IGNORE INTO wiki_links VALUES(?,?,?,'contains')").params(p,parent,id).update();
    index(p,page(p,id).orElseThrow(),"");
    return page(p,id).orElseThrow();
  }
  @Override public List<Member> members(String p,String page) {
    return db.sql("SELECT memory_id,role,score FROM wiki_memberships WHERE profile_id=? AND page_id=? ORDER BY role,memory_id")
      .params(p,page).query((r,n)->new Member(r.getString(1),r.getString(2),r.getDouble(3))).list();
  }
  @Override public List<Member> evidenceMembers(String p,String page,TemporalSnapshot s,String after,int limit) {
    return db.sql("SELECT x.memory_id,x.role,x.score FROM wiki_memberships x JOIN memories m ON m.profile_id=x.profile_id AND m.id=x.memory_id "
      +"WHERE x.profile_id=? AND x.page_id=? AND m.id>? AND "+SqliteTemporalStore.visible("m",s)
      +" AND COALESCE(m.topic_key,'') NOT LIKE 'code:summary:%' ORDER BY m.id LIMIT ?")
      .params(p,page,after,limit).query((r,n)->new Member(r.getString(1),r.getString(2),r.getDouble(3))).list();
  }
  @Override public List<Page> splitChildren(String p,String parent) {
    return db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND parent_id=? AND anchor LIKE 'split:%' ORDER BY created_at,id")
      .params(p,parent).query(this::mapPage).list();
  }
  @Override public void replaceMembers(String p,String page,List<String> ids) {
    db.sql("DELETE FROM wiki_memberships WHERE profile_id=? AND page_id=?").params(p,page).update();
    for(String id:ids) membership(p,page,id,"core",1);
  }
  @Override public List<String> placements(String p,String id) {
    return db.sql("SELECT page_id FROM wiki_memberships WHERE profile_id=? AND memory_id=? ORDER BY page_id")
      .params(p,id).query(String.class).list();
  }
  @Override public void membership(String p,String page,String id,String role,double score) {
    db.sql("INSERT INTO wiki_memberships VALUES(?,?,?,?,?) ON CONFLICT(profile_id,page_id,memory_id) DO UPDATE SET "
        +"role=CASE WHEN role='core' THEN role ELSE excluded.role END,score=MAX(score,excluded.score)")
      .params(p,page,id,role,score).update();
  }
  @Override public void removeMemberships(String p,String id) {
    db.sql("DELETE FROM wiki_memberships WHERE profile_id=? AND memory_id=? AND page_id IN "
      +"(SELECT id FROM wiki_pages WHERE profile_id=? AND anchor NOT LIKE 'split:%' AND anchor NOT LIKE 'cluster:%')").params(p,id,p).update();
  }
  private record ClusterTopic(String id,String title,int revision,String state) {}
  @Override public void syncClusters(String p,TemporalSnapshot s) {
    // A cluster has one stable page. Existing topic pages retain their URLs as legacy entries.
    // Select every graph revision at the same watermark as the source scan.
    List<ClusterTopic> topics=db.sql("""
      SELECT id,title,revision,state FROM (
        SELECT c.id,r.title,r.revision,r.state,
          ROW_NUMBER() OVER (PARTITION BY c.id ORDER BY r.revision DESC) AS n
        FROM clusters c JOIN cluster_structure_revisions r ON r.cluster_id=c.id
        WHERE c.profile_id=? AND r.recorded_sequence<=? AND r.recorded_at<=?
      ) WHERE n=1
      """).params(p,s.sequence(),time(s.knownAsOf()))
      .query((r,n)->new ClusterTopic(r.getString(1),r.getString(2),r.getInt(3),r.getString(4))).list();
    Map<String,ClusterTopic> active=new TreeMap<>();
    for(ClusterTopic topic:topics) if(topic.state().equals("active")) active.put(topic.id(),topic);
    Map<String,List<String>> children=new HashMap<>();
    Map<String,SortedSet<String>> parents=new HashMap<>();
    for(ClusterTopic topic:active.values()) {
      List<String> nested=db.sql("SELECT child_id FROM cluster_memberships WHERE cluster_id=? AND revision=? AND child_kind='cluster' ORDER BY child_id")
        .params(topic.id(),topic.revision()).query(String.class).list().stream().filter(active::containsKey).toList();
      children.put(topic.id(),nested);
      for(String child:nested) parents.computeIfAbsent(child,ignored->new TreeSet<>()).add(topic.id());
    }
    Page root=ensurePage(p,"directory","Topics","directory:Topics",null);
    Map<String,Page> pages=new HashMap<>();
    for(ClusterTopic topic:active.values())
      pages.put(topic.id(),ensurePage(p,"topic",topic.title(),"cluster:"+topic.id(),root.id()));
    for(ClusterTopic topic:active.values()) {
      Page page=pages.get(topic.id());
      SortedSet<String> choices=parents.getOrDefault(topic.id(),new TreeSet<>());
      String selected=choices.stream().map(pages::get).filter(Objects::nonNull)
        .map(Page::id).filter(id->id.equals(page.parentId())).findFirst()
        .orElseGet(()->choices.isEmpty()?root.id():pages.get(choices.first()).id());
      if(!page.title().equals(topic.title()) || !Objects.equals(page.parentId(),selected))
        db.sql("UPDATE wiki_pages SET title=?,parent_id=?,revision=revision+1,scope_revision=scope_revision+1 WHERE profile_id=? AND id=?")
          .params(topic.title(),selected,p,page.id()).update();
      Set<String> originals=new TreeSet<>();
      collectClusterSources(topic.id(),active,children,originals,new HashSet<>());
      replaceMembers(p,page.id(),List.copyOf(originals));
      refreshPage(p,page.id(),s);
    }
    db.sql("DELETE FROM wiki_links WHERE profile_id=? AND kind='contains' AND target_id IN "
      +"(SELECT id FROM wiki_pages WHERE profile_id=? AND anchor LIKE 'cluster:%')").params(p,p).update();
    db.sql("DELETE FROM wiki_links WHERE profile_id=? AND kind='cluster-parent'").param(p).update();
    for(ClusterTopic topic:active.values()) {
      Page page=page(p,pages.get(topic.id()).id()).orElseThrow();
      db.sql("INSERT OR IGNORE INTO wiki_links VALUES(?,?,?,'contains')")
        .params(p,page.parentId(),page.id()).update();
    }
    for(var relation:children.entrySet()) for(String child:relation.getValue())
      db.sql("INSERT OR IGNORE INTO wiki_links VALUES(?,?,?,'cluster-parent')")
        .params(p,pages.get(relation.getKey()).id(),pages.get(child).id()).update();
    for(Page page:db.sql("SELECT * FROM wiki_pages WHERE profile_id=? AND anchor LIKE 'cluster:%'")
        .param(p).query(this::mapPage).list()) {
      if(active.containsKey(page.anchor().substring("cluster:".length()))) continue;
      replaceMembers(p,page.id(),List.of());
      db.sql("UPDATE wiki_pages SET lifecycle='retired',revision=revision+1,scope_revision=scope_revision+1 WHERE profile_id=? AND id=? AND lifecycle<>'retired'")
        .params(p,page.id()).update();
      index(p,page(p,page.id()).orElseThrow(),"");
    }
  }
  private void collectClusterSources(String id,Map<String,ClusterTopic> active,Map<String,List<String>> children,
      Set<String> originals,Set<String> visited) {
    if(!visited.add(id)) return;
    ClusterTopic topic=active.get(id);
    originals.addAll(db.sql("SELECT child_id FROM cluster_memberships WHERE cluster_id=? AND revision=? AND child_kind='memory'")
      .params(id,topic.revision()).query(String.class).list());
    for(String child:children.getOrDefault(id,List.of())) collectClusterSources(child,active,children,originals,visited);
  }
  @Override public List<ClusterClaim> clusterClaims(String p,Page page,TemporalSnapshot s,List<Evidence> selected) {
    if(!page.anchor().startsWith("cluster:")) return List.of();
    String id=page.anchor().substring("cluster:".length());
    var synthesis=memories.readClusters(c->c.synthesis(p,id,s)).orElse(null);
    if(synthesis==null) return List.of();
    Map<String,String> refs=new HashMap<>();
    for(Evidence evidence:selected) refs.put(evidence.memoryId(),evidence.ref());
    List<ClusterClaim> claims=new ArrayList<>();
    for(var claim:synthesis.claims()) {
      List<String> citations=claim.supports().stream().map(source->refs.get(source.memoryId()))
        .filter(Objects::nonNull).distinct().toList();
      if(citations.size()==claim.supports().stream().map(dev.alvo.pieria.cluster.ClusterRepository.Source::memoryId).distinct().count())
        claims.add(new ClusterClaim(claim.text(),citations));
    }
    return claims;
  }
  @Override public void refreshPage(String p,String id,TemporalSnapshot s) {
    Page page=page(p,id).orElseThrow();
    if(page.lifecycle().equals("redirect") || page.anchor().startsWith("directory:")) return;
    if(page.kind().equals("directory")) {refreshDirectory(p,page,s);return;}
    var ids=new ArrayList<String>(); var excerpt=new StringBuilder();
    var live=new ArrayList<Source>();
    for(Member m:members(p,id)) source(p,m.memoryId(),s).ifPresent(src->{
      ids.add(src.id()+":"+m.role()+":"+json.writeValueAsString(src.validity())+":"+src.payload());live.add(src);
      if(excerpt.length()<2000) excerpt.append(src.content(),0,Math.min(src.content().length(),300)).append(' ');
    });
    String synthesisId=page.anchor().startsWith("cluster:")
      ? memories.readClusters(c->c.synthesis(p,page.anchor().substring("cluster:".length()),s))
          .map(dev.alvo.pieria.cluster.ClusterRepository.Synthesis::id).orElse("") : "";
    String signature=Hash.hash128(String.join("\n",ids),synthesisId);
    String lifecycle=live.isEmpty()?"retired":"active";
    String title=page.title();
    if(title.isBlank() && !live.isEmpty()) {
      title=dev.alvo.pieria.wiki.WikiText.title(page.anchor());
      if(page.anchor().startsWith("split:")) title=live.getFirst().content().substring(0,Math.min(70,live.getFirst().content().length()));
      if(page.kind().equals("entity")) title=live.stream().flatMap(x->x.entities().stream())
        .filter(a->page.anchor().equals("entity:"+a.id())).map(Anchor::name).findFirst().orElse(title);
    }
    if(!signature.equals(page.signature()) || page.suppressed() || !lifecycle.equals(page.lifecycle()))
      db.sql("UPDATE wiki_pages SET title=?,signature=?,lifecycle=?,suppressed=0,revision=revision+1,scope_revision=scope_revision+1 WHERE profile_id=? AND id=?")
        .params(title,signature,lifecycle,p,id).update();
    db.sql("DELETE FROM wiki_dependencies WHERE profile_id=? AND owner_kind='page' AND owner_id=?").params(p,id).update();
    for(Source src:live) dependency(p,"page",id,src.id());
    index(p,page(p,id).orElseThrow(),excerpt.toString());
  }
  private void refreshDirectory(String p,Page page,TemporalSnapshot snapshot) {
    int count=evidenceCount(p,page.id(),snapshot);
    List<Source> sample=evidenceMembers(p,page.id(),snapshot,"",3).stream()
      .map(m->source(p,m.memoryId(),snapshot)).flatMap(Optional::stream).toList();
    String signature=Hash.hash128(Integer.toString(count),sample.stream().map(Source::id).toList().toString());
    String lifecycle=count==0?"retired":"active";
    String title=page.title().isBlank()?dev.alvo.pieria.wiki.WikiText.title(page.anchor()):page.title();
    if(!signature.equals(page.signature()) || page.suppressed() || !lifecycle.equals(page.lifecycle()))
      db.sql("UPDATE wiki_pages SET title=?,signature=?,lifecycle=?,suppressed=0,revision=revision+1,scope_revision=scope_revision+1 WHERE profile_id=? AND id=?")
        .params(title,signature,lifecycle,p,page.id()).update();
    db.sql("DELETE FROM wiki_dependencies WHERE profile_id=? AND owner_kind='page' AND owner_id=?")
      .params(p,page.id()).update();
    for(Source source:sample) dependency(p,"page",page.id(),source.id());
    String excerpts=sample.stream().map(Source::content).map(content->content.substring(0,Math.min(300,content.length())))
      .collect(java.util.stream.Collectors.joining(" "));
    index(p,page(p,page.id()).orElseThrow(),excerpts);
  }
  private void index(String p,Page page,String excerpts) {
    db.sql("DELETE FROM wiki_fts WHERE profile_id=? AND page_id=?").params(p,page.id()).update();
    if(!page.suppressed() && page.lifecycle().equals("active"))
      db.sql("INSERT INTO wiki_fts(title,anchor,excerpts,profile_id,page_id) VALUES(?,?,?,?,?)")
        .params(page.title(),page.anchor(),excerpts,p,page.id()).update();
  }
  @Override public void checkpoint(String p,State s) {
    db.sql("INSERT INTO wiki_state VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET cursor=excluded.cursor,checkpoint=excluded.checkpoint,"
        +"initialized=excluded.initialized,revision=excluded.revision,policy=excluded.policy,next_boundary=excluded.next_boundary,target_sequence=excluded.target_sequence,scan_snapshot=excluded.scan_snapshot")
      .params(p,s.cursor(),s.checkpoint(),s.initialized()?1:0,s.revision(),s.policy(),time(s.nextBoundary()),s.targetSequence(),s.scanSnapshot()==null?null:json.writeValueAsString(s.scanSnapshot())).update();
  }
  @Override public void checked(String p,String id,long seq,Instant boundary) {
    db.sql("UPDATE wiki_pages SET checked_sequence=?,next_boundary=? WHERE profile_id=? AND id=?").params(seq,time(boundary),p,id).update();
  }
  @Override public List<Link> links(String p,String id) {
    return db.sql("SELECT DISTINCT q.id,q.title,x.kind FROM wiki_links x JOIN wiki_pages q ON q.profile_id=x.profile_id AND "
        +"q.id=CASE WHEN x.source_id=? THEN x.target_id ELSE x.source_id END WHERE x.profile_id=? AND (x.source_id=? OR x.target_id=?) "
        +"AND q.suppressed=0 AND q.lifecycle='active' ORDER BY q.title,q.id LIMIT 50")
      .params(id,p,id,id).query((r,n)->new Link(r.getString(1),r.getString(2),r.getString(3))).list();
  }
  @Override public void related(String p,String from,String to) {
    if(!from.equals(to)) db.sql("INSERT OR IGNORE INTO wiki_links VALUES(?,?,?,'related')").params(p,from,to).update();
  }
  @Override public String canonical(String p,String id) {
    Set<String> seen=new HashSet<>();
    while(seen.add(id)) {
      String target=db.sql("SELECT target_id FROM wiki_redirects WHERE profile_id=? AND source_id=?").params(p,id).query(String.class).optional().orElse(null);
      if(target==null) return id;
      id=target;
    }
    throw new IllegalStateException("Wiki redirect cycle");
  }
  @Override public boolean confirmMerge(String p,String left,String right,String signature,String round) {
    boolean confirmed=count("SELECT COUNT(*) FROM wiki_merge_candidates WHERE profile_id=? AND left_id=? AND right_id=? AND signature=? AND observed_round<>?",
      p,left,right,signature,round)>0;
    db.sql("INSERT INTO wiki_merge_candidates VALUES(?,?,?,?,?,?) ON CONFLICT(profile_id,left_id,right_id) DO UPDATE SET "
      +"observed_round=CASE WHEN signature=excluded.signature THEN observed_round ELSE excluded.observed_round END,signature=excluded.signature,last_seen_round=excluded.last_seen_round")
      .params(p,left,right,signature,round,round).update();
    return confirmed;
  }
  @Override public boolean pendingMerges(String p) {
    return count("SELECT COUNT(*) FROM wiki_merge_candidates WHERE profile_id=?",p)>0;
  }
  @Override public void finishMergeRound(String p,String round) {
    db.sql("DELETE FROM wiki_merge_candidates WHERE profile_id=? AND last_seen_round<>?").params(p,round).update();
  }
  @Override public void merge(String p,String from,String into) {
    if(from.equals(into)) return;
    for(Member m:members(p,from)) membership(p,into,m.memoryId(),m.role(),m.score());
    db.sql("UPDATE wiki_pages SET lifecycle='redirect',revision=revision+1 WHERE profile_id=? AND id=?").params(p,from).update();
    db.sql("INSERT OR REPLACE INTO wiki_redirects VALUES(?,?,?)").params(p,from,into).update();
    db.sql("UPDATE wiki_redirects SET target_id=? WHERE profile_id=? AND target_id=?").params(into,p,from).update();
    db.sql("DELETE FROM wiki_fts WHERE profile_id=? AND page_id=?").params(p,from).update();
    db.sql("DELETE FROM wiki_merge_candidates WHERE profile_id=? AND (left_id=? OR right_id=?)").params(p,from,from).update();
  }
  @Override public List<String> searchPages(String p,String match,int limit) {
    return db.sql("SELECT f.page_id FROM wiki_fts f JOIN wiki_pages q ON q.profile_id=f.profile_id AND q.id=f.page_id "
        +"WHERE wiki_fts MATCH ? AND f.profile_id=? AND q.suppressed=0 AND q.lifecycle='active' ORDER BY rank,f.page_id LIMIT ?")
      .params(match,p,limit).query(String.class).list();
  }
  @Override public List<Source> searchSources(String p,String query,float[] embedding,TemporalSnapshot s,int limit) {
    String match=dev.alvo.pieria.wiki.WikiText.match(query);
    Map<String,Double> scores=new HashMap<>();
    List<List<Memory>> routes=new ArrayList<>();
    if(!match.isBlank()) routes.add(memories.atSnapshot(s,()->memories.searchMemoriesFts(p,match,limit)));
    routes.add(memories.atSnapshot(s,()->memories.exactKeyLookup(p,List.of(query.strip()),limit)));
    if(embedding!=null) routes.add(memories.atSnapshot(s,()->memories.vectorSearch(p,embedding,limit)));
    for(List<Memory> route:routes) for(int i=0;i<route.size();i++) scores.merge(route.get(i).id(),1.0/(61+i),Double::sum);
    return scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id->id))
      .map(id->source(p,id,s)).flatMap(Optional::stream).limit(limit).toList();
  }
  @Override public Optional<Version> version(String p,String id) {
    if(id==null) return Optional.empty();
    return db.sql("SELECT * FROM wiki_versions WHERE profile_id=? AND id=? AND suppressed=0")
      .params(p,id).query((r,n)->new Version(r.getString("id"),r.getString("page_id"),r.getString("fingerprint"),r.getString("body"),
        json.readValue(r.getString("bundle"),Bundle.class),instant(r.getString("created_at")))).optional()
      .filter(v->!forgotten(p,v.bundle()));
  }
  private Job mapJob(ResultSet r,int n) throws SQLException {
    String bundle=r.getString("bundle");
    return new Job(r.getString("id"),r.getString("profile_id"),r.getString("page_id"),r.getString("fingerprint"),r.getString("status"),r.getInt("attempts"),
      r.getLong("fence"),instant(r.getString("lease_until")),r.getString("error_code"),r.getString("result_version"),
      bundle.equals("{}")?null:json.readValue(bundle,Bundle.class));
  }
  @Override public Optional<Job> job(String p,String id) { return db.sql("SELECT * FROM wiki_jobs WHERE profile_id=? AND id=?").params(p,id).query(this::mapJob).optional(); }
  @Override public Optional<Job> latestJob(String p,String page) {
    return db.sql("SELECT * FROM wiki_jobs WHERE profile_id=? AND page_id=? ORDER BY created_at DESC,id DESC LIMIT 1")
      .params(p,page).query(this::mapJob).optional();
  }
  @Override public Optional<Job> liveJob(String p,String page) {
    return db.sql("SELECT * FROM wiki_jobs WHERE profile_id=? AND page_id=? AND status IN ('queued','generating') ORDER BY created_at LIMIT 1")
      .params(p,page).query(this::mapJob).optional();
  }
  private void dependency(String p,String kind,String owner,String memory) {
    db.sql("INSERT OR IGNORE INTO wiki_dependencies VALUES(?,?,?,?)").params(p,kind,owner,memory).update();
  }
  @Override public Job enqueue(String p,Bundle b,int queueLimit) {
    var live=liveJob(p,b.pageId());
    if(live.isPresent() && live.get().fingerprint().equals(b.fingerprint())) return live.get();
    live.ifPresent(j->finish(j,"obsolete","inputs_changed",null));
    if(count("SELECT COUNT(*) FROM wiki_jobs WHERE status IN ('queued','generating')")>=queueLimit)
      throw new dev.alvo.pieria.wiki.WikiException(429,"queue_full");
    String id=UUID.randomUUID().toString();
    db.sql("INSERT INTO wiki_jobs(profile_id,id,page_id,fingerprint,bundle,available_at,created_at) VALUES(?,?,?,?,?,?,?)")
      .params(p,id,b.pageId(),b.fingerprint(),json.writeValueAsString(b),time(Instant.now()),time(Instant.now())).update();
    for(Evidence e:b.evidence()) dependency(p,"job",id,e.memoryId());
    for(Member m:members(p,b.pageId())) dependency(p,"job",id,m.memoryId());
    for(Link link:b.links()) for(Member m:members(p,link.id())) dependency(p,"job",id,m.memoryId());
    return job(p,id).orElseThrow();
  }
  @Override public Optional<Job> claim(String p,Instant now,int attempts) {
    // Expired work cannot publish; fence recovery even when the last attempt was exhausted.
    db.sql("UPDATE wiki_jobs SET status=CASE WHEN attempts>=? THEN 'failed' ELSE 'queued' END,fence=fence+1,error_code='lease_expired' "
        +"WHERE profile_id=? AND status='generating' AND lease_until<=?").params(attempts,p,time(now)).update();
    if(count("SELECT COUNT(*) FROM wiki_jobs WHERE status='generating' AND lease_until>?",time(now))>0) return Optional.empty();
    var next=db.sql("SELECT * FROM wiki_jobs WHERE profile_id=? AND status='queued' AND available_at<=? ORDER BY created_at,id LIMIT 1")
      .params(p,time(now)).query(this::mapJob).optional();
    if(next.isEmpty()) return next;
    Job j=next.get();
    db.sql("UPDATE wiki_jobs SET status='generating',attempts=attempts+1,fence=fence+1,lease_until=? WHERE profile_id=? AND id=?")
      .params(time(now.plusSeconds(30)),p,j.id()).update();
    return job(p,j.id());
  }
  @Override public boolean heartbeat(Job j,Instant now) {
    return db.sql("UPDATE wiki_jobs SET lease_until=? WHERE profile_id=? AND id=? AND fence=? AND status='generating' AND lease_until>?")
      .params(time(now.plusSeconds(30)),j.profileId(),j.id(),j.fence(),time(now)).update()==1;
  }
  @Override public void finish(Job j,String status,String code,Instant retryAt) {
    db.sql("UPDATE wiki_jobs SET status=?,error_code=?,available_at=?,lease_until=NULL WHERE profile_id=? AND id=? AND fence=? AND status IN ('queued','generating')")
      .params(status,code,time(retryAt==null?Instant.now():retryAt),j.profileId(),j.id(),j.fence()).update();
  }
  @Override public boolean forgotten(String p,Bundle b) {
    if(b==null || !exists(p) || page(p,b.pageId()).map(Page::suppressed).orElse(true)) return true;
    for(Evidence e:b.evidence())
      if(count("SELECT COUNT(*) FROM memories WHERE profile_id=? AND id=? AND forgotten=0",p,e.memoryId())==0) return true;
    return false;
  }
  @Override public boolean publish(Job j,Bundle current,String body) {
    if(!exists(j.profileId()) || !j.fingerprint().equals(current.fingerprint()) || forgotten(j.profileId(),current)) return false;
    State state=state(j.profileId());Page page=page(j.profileId(),j.pageId()).orElseThrow();
    if(!state.initialized() || state.checkpoint()!=watermark(j.profileId()) || current.watermark()!=state.checkpoint()
        || page.scopeRevision()!=current.scopeRevision() || !page.title().equals(current.title())
        || current.nextBoundary()!=null && !current.nextBoundary().isAfter(Instant.now())) return false;
    var active=job(j.profileId(),j.id());
    if(active.isEmpty() || !active.get().status().equals("generating") || active.get().fence()!=j.fence()
        || active.get().leaseUntil()==null || !active.get().leaseUntil().isAfter(Instant.now())) return false;
    String id=Hash.hash128(j.profileId(),j.pageId(),j.fingerprint());
    db.sql("INSERT OR IGNORE INTO wiki_versions VALUES(?,?,?,?,?,?,0,?)")
      .params(j.profileId(),id,j.pageId(),j.fingerprint(),body,json.writeValueAsString(current),time(Instant.now())).update();
    // A suppressed fingerprint can never be resurrected by INSERT OR IGNORE.
    if(version(j.profileId(),id).isEmpty()) return false;
    db.sql("INSERT OR IGNORE INTO wiki_dependencies SELECT profile_id,'version',?,memory_id FROM wiki_dependencies WHERE profile_id=? AND owner_kind='job' AND owner_id=?")
      .params(id,j.profileId(),j.id()).update();
    db.sql("UPDATE wiki_pages SET current_version=?,checked_sequence=?,next_boundary=? WHERE profile_id=? AND id=? AND suppressed=0")
      .params(id,current.watermark(),time(current.nextBoundary()),j.profileId(),j.pageId()).update();
    db.sql("UPDATE wiki_jobs SET status='succeeded',result_version=?,bundle='{}',lease_until=NULL WHERE profile_id=? AND id=? AND fence=?")
      .params(id,j.profileId(),j.id(),j.fence()).update();
    return true;
  }
  void deleteProfile(String p) {
    db.sql("DELETE FROM wiki_fts WHERE profile_id=?").param(p).update();
    for(String table:List.of("wiki_dependencies","wiki_jobs","wiki_versions","wiki_links","wiki_redirects","wiki_memberships","wiki_merge_candidates","wiki_pages","wiki_changes","wiki_state"))
      db.sql("DELETE FROM "+table+" WHERE profile_id=?").param(p).update();
  }
}
