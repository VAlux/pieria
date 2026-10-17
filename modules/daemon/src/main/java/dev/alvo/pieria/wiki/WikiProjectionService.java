package dev.alvo.pieria.wiki;

import dev.alvo.pieria.config.WikiProperties;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.tools.TextSimilarity;
import dev.alvo.pieria.tools.Tokens;
import dev.alvo.pieria.tools.Vectors;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static dev.alvo.pieria.wiki.WikiData.*;

/** Resumable, model-free projection. Conservative reconciliation never pays for narrative refresh. */
@Service
public class WikiProjectionService {
  // Candidate search and page assignment run inside the SQLite write transaction. A single
  // source can take seconds on a large profile, so commit before taking the next source.
  private static final int SQLITE_WRITE_BATCH_LIMIT = 1;
  private final MemoryStore store;
  public WikiProjectionService(MemoryStore store) { this.store=store; }
  public static String policy(WikiProperties cfg) {
    return Hash.hash128("wiki-projection-1",Double.toString(cfg.semanticAdmit()),Double.toString(cfg.semanticRetain()),
      Double.toString(cfg.lexicalAdmit()),Double.toString(cfg.lexicalRetain()),Double.toString(cfg.subjectLexical()),
      Integer.toString(cfg.splitSources()),Integer.toString(cfg.splitTokens()),Double.toString(cfg.mergeOverlap()),
      Double.toString(cfg.mergeCosine()),Double.toString(cfg.mergeLexical()));
  }
  public boolean tick(String profile,WikiProperties cfg) {
    if(!cfg.enabled()) return false;
    return store.writeWiki(w-> {
      if(!w.exists(profile)) return false;
      State state=w.state(profile); long watermark=w.watermark(profile);
      String policy=policy(cfg);
      boolean boundary=state.nextBoundary()!=null && !state.nextBoundary().isAfter(Instant.now());
      if(state.initialized() && state.checkpoint()==watermark && state.policy().equals(policy) && !boundary && !w.pendingMerges(profile)) return false;
      TemporalSnapshot snapshot=state.scanSnapshot();
      long target=state.targetSequence(); String cursor=state.cursor();
      if(snapshot==null || state.initialized() || !state.policy().equals(policy)) {
        snapshot=w.snapshot(); target=watermark; cursor="seed:";
        // With bounded semantic routing, a full, paginated source recheck is the completeness fallback.
        // Existing identities and core anchors are retained; this is not an all-pairs recluster.
      }
      TemporalSnapshot selected=snapshot;
      if(!cursor.startsWith("pages:")) {
        roots(w,profile);
        boolean seeding=cursor.startsWith("seed:");
        String start=cursor.substring(cursor.indexOf(':')+1);
        int batchLimit=Math.min(cfg.batchSize(),SQLITE_WRITE_BATCH_LIMIT);
        List<Source> batch=w.sources(profile,selected,start,batchLimit);
        for(Source source:batch) {
          if(seeding) anchors(w,profile,source);else assign(w,profile,source,selected,cfg);
        }
        cursor=batch.size()<batchLimit?(seeding?"sources:":"pages:"):(seeding?"seed:":"sources:")+batch.getLast().id();
      } else {
        int batchLimit=Math.min(cfg.batchSize(),SQLITE_WRITE_BATCH_LIMIT);
        List<Page> pages=w.pages(profile,cursor.substring(6),batchLimit);
        int operations=0,processed=0;
        for(Page page:pages) {
          if(operations>=cfg.structuralLimit()) break;
          w.refreshPage(profile,page.id(),selected);
          operations+=reorganize(w,profile,w.page(profile,page.id()).orElseThrow(),selected,cfg);
          processed++;
        }
        if(processed==pages.size() && pages.size()<batchLimit) {
          w.syncClusters(profile,selected);
          w.finishMergeRound(profile,selected.asOf().toString());
          w.checkpoint(profile,new State("",target,true,state.revision()+1,policy,w.nextBoundary(profile,selected.asOf()),target,null));
          return true;
        }
        cursor="pages:"+pages.get(processed-1).id();
      }
      w.checkpoint(profile,new State(cursor,state.checkpoint(),false,state.revision()+1,policy,state.nextBoundary(),target,selected));
      return true;
    });
  }
  private void roots(WikiRepository w,String p) {
    for(String name:List.of("Topics","Entities","Code","Unassigned Evidence"))
      w.ensurePage(p,"directory",name,"directory:"+name,null);
  }
  private String root(WikiRepository w,String p,String name) { return w.anchor(p,"directory:"+name).orElseThrow().id(); }
  private void assign(WikiRepository w,String p,Source s,TemporalSnapshot snapshot,WikiProperties cfg) {
    var prior=new HashSet<>(w.placements(p,s.id()));
    var candidates=new LinkedHashSet<String>(prior);
    for(String neighbor:w.candidateMemories(p,s,snapshot,20)) candidates.addAll(w.placements(p,neighbor));
    Set<String> core=anchors(w,p,s);
    for(String id:core) for(Member m:w.members(p,id).stream().limit(50).toList()) candidates.addAll(w.placements(p,m.memoryId()));
    record Choice(String page,double score) {}
    List<Choice> choices=new ArrayList<>();
    for(String id:candidates.stream().limit(50).toList()) {
      Page page=w.page(p,w.canonical(p,id)).orElse(null);
      if(page==null || page.kind().equals("directory") || page.anchor().startsWith("split:") || page.anchor().startsWith("cluster:") || core.contains(page.id())) continue;
      double best=-1;
      for(Member member:w.members(p,page.id()).stream().filter(m->m.role().equals("core")).limit(8).toList()) {
        Source representative=w.source(p,member.memoryId(),snapshot).orElse(null);
        if(representative==null) continue;
        double lexical=TextSimilarity.similarity(s.content(),representative.content());
        boolean compatible=sharedSubject(w,p,s,representative,snapshot) || lexical>=cfg.subjectLexical();
        if(!compatible) continue;
        boolean vectors=s.embedding()!=null && representative.embedding()!=null && s.embedding().length==representative.embedding().length;
        double score=vectors?Vectors.cosine(s.embedding(),representative.embedding()):lexical;
        double threshold=vectors?(prior.contains(page.id())?cfg.semanticRetain():cfg.semanticAdmit()):(prior.contains(page.id())?cfg.lexicalRetain():cfg.lexicalAdmit());
        if(score>=threshold) best=Math.max(best,score);
      }
      if(best>=0) choices.add(new Choice(page.id(),best));
    }
    w.removeMemberships(p,s.id());
    for(String id:core) w.membership(p,id,s.id(),"core",1);
    choices.sort(Comparator.comparingDouble(Choice::score).reversed().thenComparing(Choice::page));
    for(Choice choice:choices.stream().limit(3).toList()) {
      w.membership(p,choice.page(),s.id(),"contextual",choice.score());
      for(String id:core) w.related(p,id,choice.page());
    }
    // Unassigned is a source directory, never an automatically invented article.
    if(core.isEmpty() && choices.isEmpty()) w.membership(p,root(w,p,"Unassigned Evidence"),s.id(),"core",1);
  }
  private Set<String> anchors(WikiRepository w,String p,Source s) {
    Set<String> core=new LinkedHashSet<>();
    if(s.topicKey()!=null && !s.topicKey().isBlank()) {
      boolean code=s.topicKey().startsWith("code:"); String anchor="topic:"+s.topicKey();
      String namespace=s.topicKey().split(":|\\.",2)[0];
      Page group=w.ensurePage(p,"directory",WikiText.title(namespace),"group:"+(code?"code:":"topic:")+namespace,root(w,p,code?"Code":"Topics"));
      Page page=w.ensurePage(p,"topic",WikiText.title(s.topicKey()),anchor,group.id());core.add(group.id());core.add(page.id());
    }
    for(Anchor entity:s.entities()) {
      Page group=w.ensurePage(p,"directory",WikiText.title(entity.type()),"group:entity:"+entity.type(),root(w,p,"Entities"));
      Page page=w.ensurePage(p,"entity",entity.name(),"entity:"+entity.id(),group.id());core.add(group.id());core.add(page.id());
    }
    for(String id:core) w.membership(p,id,s.id(),"core",1);
    return core;
  }
  private int reorganize(WikiRepository w,String p,Page page,TemporalSnapshot s,WikiProperties cfg) {
    if(!page.kind().equals("topic") || !page.lifecycle().equals("active") || page.anchor().startsWith("split:") || page.anchor().startsWith("cluster:")) return 0;
    List<Source> core=w.members(p,page.id()).stream().filter(m->m.role().equals("core"))
      .map(m->w.source(p,m.memoryId(),s)).flatMap(Optional::stream).toList();
    long tokens=core.stream().mapToLong(x->Tokens.estimate(x.content())).sum();
    if(core.size()>cfg.splitSources() || tokens>cfg.splitTokens()) {
      List<List<Source>> coherent=partitions(core,cfg).stream().filter(g->g.size()>=3).toList();
      if(coherent.size()>=2) {
        List<Page> previous=w.splitChildren(p,page.id());Set<String> reused=new HashSet<>();
        for(List<Source> group:coherent) {
          Set<String> ids=new TreeSet<>();group.forEach(src->ids.add(src.id()));
          Page child=null;double overlap=.5;
          for(Page candidate:previous) {
            if(reused.contains(candidate.id())) continue;
            Set<String> old=new HashSet<>();w.members(p,candidate.id()).forEach(m->old.add(m.memoryId()));
            double score=TextSimilarity.jaccard(ids,old);
            if(score>=overlap) {child=candidate;overlap=score;}
          }
          if(child==null) {
            Source seed=group.getFirst();String title=seed.content().substring(0,Math.min(70,seed.content().length()));
            child=w.ensurePage(p,"topic",title,"split:"+page.id()+":"+UUID.randomUUID(),page.id());
          }
          reused.add(child.id());w.replaceMembers(p,child.id(),List.copyOf(ids));w.refreshPage(p,child.id(),s);
        }
        for(Page child:previous) if(!reused.contains(child.id())) {w.replaceMembers(p,child.id(),List.of());w.refreshPage(p,child.id(),s);}
        return 1;
      }
    }
    // Smaller overviews retain their existing narrower subjects, but cannot retain withdrawn sources.
    Set<String> current=new HashSet<>();core.forEach(src->current.add(src.id()));
    for(Page child:w.splitChildren(p,page.id())) {
      w.replaceMembers(p,child.id(),w.members(p,child.id()).stream().map(Member::memoryId).filter(current::contains).toList());
      w.refreshPage(p,child.id(),s);
    }
    // Only consider pages already connected through overlapping evidence: no all-pages pair scan.
    Set<String> peers=new TreeSet<>();for(Member member:w.members(p,page.id())) peers.addAll(w.placements(p,member.memoryId()));
    for(String peer:peers) {
      Page other=w.page(p,w.canonical(p,peer)).orElse(null);
      if(other==null || other.id().equals(page.id()) || other.anchor().startsWith("cluster:") || !other.kind().equals("topic") || !other.lifecycle().equals("active")
          || !Objects.equals(other.parentId(),page.parentId()) || other.anchor().startsWith("split:")) continue;
      Set<String> left=new HashSet<>(),right=new HashSet<>();
      w.members(p,page.id()).forEach(m->left.add(m.memoryId())); w.members(p,other.id()).forEach(m->right.add(m.memoryId()));
      if(TextSimilarity.jaccard(left,right)<cfg.mergeOverlap() || core.isEmpty()) continue;
      List<Source> others=w.members(p,other.id()).stream().filter(m->m.role().equals("core")).limit(8)
        .map(m->w.source(p,m.memoryId(),s)).flatMap(Optional::stream).toList();
      boolean compatible=false;
      for(Source a:core.stream().limit(8).toList()) for(Source b:others) {
        boolean anchor=sharedSubject(w,p,a,b,s);
        boolean vector=a.embedding()!=null && b.embedding()!=null && a.embedding().length==b.embedding().length;
        double score=vector?Vectors.cosine(a.embedding(),b.embedding()):TextSimilarity.similarity(a.content(),b.content());
        if(anchor && score>=(vector?cfg.mergeCosine():cfg.mergeLexical())) compatible=true;
      }
      if(!compatible) continue;
      Page keep=Comparator.comparing(Page::createdAt).thenComparing(Page::id).compare(page,other)<=0?page:other;
      Page remove=keep.id().equals(page.id())?other:page;
      if(w.confirmMerge(p,keep.id(),remove.id(),Hash.hash128(keep.signature(),remove.signature()),s.asOf().toString())) {
        w.merge(p,remove.id(),keep.id());w.refreshPage(p,keep.id(),s);return 1;
      }
    }
    return 0;
  }

  private boolean sharedSubject(WikiRepository w,String p,Source a,Source b,TemporalSnapshot snapshot) {
    return a.topicKey()!=null && a.topicKey().equals(b.topicKey())
      || a.entities().stream().anyMatch(e->b.entities().stream().anyMatch(other->other.id().equals(e.id()))
          && w.distinctiveEntity(p,e.id(),snapshot));
  }
  private double similarity(Source a,Source b) {
    return a.embedding()!=null && b.embedding()!=null && a.embedding().length==b.embedding().length
      ? Vectors.cosine(a.embedding(),b.embedding()):TextSimilarity.similarity(a.content(),b.content());
  }
  private double admission(Source a,Source b,WikiProperties cfg) {
    return a.embedding()!=null && b.embedding()!=null && a.embedding().length==b.embedding().length
      ? cfg.semanticAdmit():cfg.lexicalAdmit();
  }
  /** Stable farthest-first seeds; unmatched evidence remains on the overview. */
  private List<List<Source>> partitions(List<Source> sources,WikiProperties cfg) {
    List<Source> ordered=sources.stream().sorted(Comparator.comparing(Source::id)).toList();
    List<Source> seeds=new ArrayList<>();seeds.add(ordered.getFirst());
    while(seeds.size()<Math.min(16,ordered.size())) {
      Source farthest=null;double lowest=Double.POSITIVE_INFINITY;
      for(Source candidate:ordered) {
        if(seeds.contains(candidate)) continue;
        double nearest=-1;boolean admitted=false;
        for(Source seed:seeds) {double score=similarity(candidate,seed);nearest=Math.max(nearest,score);admitted|=score>=admission(candidate,seed,cfg);}
        if(!admitted && nearest<lowest) {farthest=candidate;lowest=nearest;}
      }
      if(farthest==null) break;seeds.add(farthest);
    }
    List<List<Source>> groups=new ArrayList<>();seeds.forEach(seed->groups.add(new ArrayList<>()));
    for(Source source:ordered) {
      int best=-1;double highest=-1;
      for(int i=0;i<seeds.size();i++) {
        Source seed=seeds.get(i);double score=similarity(source,seed);
        if(score>=admission(source,seed,cfg) && score>highest) {best=i;highest=score;}
      }
      if(best>=0) groups.get(best).add(source);
    }
    return groups;
  }
}
