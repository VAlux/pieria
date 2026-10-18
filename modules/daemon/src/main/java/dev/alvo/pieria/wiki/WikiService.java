package dev.alvo.pieria.wiki;

import dev.alvo.pieria.api.request.WikiEvaluateRequest;
import dev.alvo.pieria.api.response.WikiResponse;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.WikiProperties;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.springframework.stereotype.Service;
import java.util.*;
import static dev.alvo.pieria.wiki.WikiData.*;

@Service
public class WikiService {
  private final MemoryStore store;
  private final EffectiveConfigResolver config;
  private final ModelGateway models;
  private final PieriaProperties properties;
  public WikiService(MemoryStore store,EffectiveConfigResolver config,ModelGateway models,PieriaProperties properties) {
    this.store=store;this.config=config;this.models=models;this.properties=properties;
  }
  public String profile(String name) { return store.findProfile(name).orElseThrow(()->NotFoundException.profile(name)).id(); }
  public WikiProperties settings(String p) { return config.resolve(p).wiki(); }
  public String rendering(String p) {
    WikiProperties c=settings(p);
    return Hash.hash128("wiki-prompt-1",properties.model().synthesisModel(),String.valueOf(properties.model().reasoning()),
      Integer.toString(c.inputTokens()),Integer.toString(c.outputTokens()),Integer.toString(c.maxSources()));
  }
  private WikiResponse.State state(WikiRepository w,String p) {
    State s=w.state(p);return new WikiResponse.State(settings(p).enabled(),s.initialized() && s.policy().equals(WikiProjectionService.policy(settings(p))),w.watermark(p),s.checkpoint(),s.revision());
  }
  private Page require(WikiRepository w,String p,String id) {
    Page page=w.page(p,id).orElseThrow(()->new WikiException(404,"page_not_found"));
    if(!page.lifecycle().equals("redirect") && (!page.lifecycle().equals("active") || page.suppressed())) throw new WikiException(410,"page_unavailable");
    return page;
  }
  public String canonical(String name,String id) {
    String p=profile(name);return store.readWiki(w->{require(w,p,id);return w.canonical(p,id);});
  }
  private List<Map.Entry<Member,Source>> live(WikiRepository w,String p,String id) {
    var s=w.snapshot();
    return w.members(p,id).stream().map(m->w.source(p,m.memoryId(),s).map(src->Map.entry(m,src)))
      .flatMap(Optional::stream).toList();
  }
  private WikiResponse.Card card(WikiRepository w,String p,Page page) {
    var version=w.version(p,page.currentVersion());var job=w.latestJob(p,page.id());State state=w.state(p);
    String generation=version.isPresent()?"ready":job.filter(j->List.of("queued","generating","failed").contains(j.status())).map(Job::status).orElse("absent");
    String freshness=version.isEmpty()?"unavailable":!state.initialized() || !state.policy().equals(WikiProjectionService.policy(settings(p))) || state.checkpoint()!=w.watermark(p)
      || state.nextBoundary()!=null && !state.nextBoundary().isAfter(w.snapshot().asOf())?"needs-check":
      version.get().bundle().scopeRevision()!=page.scopeRevision()?"stale":"current";
    return new WikiResponse.Card(page.id(),page.title(),page.kind(),WikiText.title(page.anchor()),page.parentId(),page.revision(),
      w.evidenceCount(p,page.id(),w.snapshot()),generation,freshness);
  }
  private static WikiResponse.Evidence evidence(Evidence e) {return new WikiResponse.Evidence(e.ref(),e.memoryId(),e.content(),e.type(),e.role(),e.validity(),e.truncated());}
  private static WikiResponse.Evidence evidence(Member member,Source source,String ref) {
    return new WikiResponse.Evidence(ref,source.id(),source.content(),source.type(),member.role(),source.validity(),false);
  }
  private static WikiResponse.Job job(Job j) { return j==null?null:new WikiResponse.Job(j.id(),j.pageId(),j.status(),j.attempts(),j.errorCode(),j.resultVersion()); }
  private static int bound(int limit,int max) { if(limit<1 || limit>max) throw new WikiException(400,"invalid_limit");return limit; }
  private static String after(String cursor,long revision,String context) {
    if(cursor==null || cursor.isBlank()) return "";
    String[] parts=cursor.split(":",2);
    if(parts.length!=2 || !parts[0].equals(Hash.hash128(context,Long.toString(revision)))) throw new WikiException(409,"cursor_expired");
    return parts[1];
  }
  public WikiResponse.Directory directory(String name,String parent,String cursor,int limit) {
    String p=profile(name);bound(limit,100);
    return store.readWiki(w->{
      var state=state(w,p);if(!state.enabled()) return new WikiResponse.Directory(state,List.of(),null);
      String context=p+":directory:"+parent;String start=after(cursor,state.revision(),context);
      var pages=w.directory(p,parent,start,limit+1);
      var slice=pages.stream().limit(limit).toList();
      String next=pages.size()>limit?Hash.hash128(context,Long.toString(state.revision()))+":"+slice.getLast().id():null;
      return new WikiResponse.Directory(state,slice.stream().map(page->card(w,p,page)).toList(),next);
    });
  }
  public WikiResponse.Page page(String name,String id) {
    String p=profile(name);
    return store.readWiki(w->{
      Page page=require(w,p,w.canonical(p,id));var version=w.version(p,page.currentVersion());
      var live=page.kind().equals("directory")?w.evidenceMembers(p,page.id(),w.snapshot(),"",40).stream()
        .map(m->w.source(p,m.memoryId(),w.snapshot()).map(src->Map.entry(m,src))).flatMap(Optional::stream).toList():live(w,p,page.id());
      int total=page.kind().equals("directory")?w.evidenceCount(p,page.id(),w.snapshot()):live.size();
      var evidence=new ArrayList<WikiResponse.Evidence>();
      if(version.isPresent()) version.get().bundle().evidence().forEach(e->evidence.add(evidence(e)));
      else for(var entry:live.stream().limit(40).toList()) evidence.add(evidence(entry.getKey(),entry.getValue(),"E"+(evidence.size()+1)));
      Set<String> current=new HashSet<>();live.forEach(e->current.add(e.getValue().id()));
      Set<String> old=new HashSet<>();version.ifPresent(v->v.bundle().evidence().forEach(e->old.add(e.memoryId())));
      int added=(int)current.stream().filter(e->!old.contains(e)).count(),removed=(int)old.stream().filter(e->!current.contains(e)).count();
      WikiResponse.Card card=card(w,p,page);
      // Certify equivalence from the selected bundle, never from a persisted boolean alone.
      if(version.isPresent() && !card.freshness().equals("needs-check")) {
        try {
          Bundle selected=WikiEvidenceSelector.select(w,p,page,settings(p),version.get().bundle().rendering());
          card=new WikiResponse.Card(card.id(),card.title(),card.kind(),card.scope(),card.parentId(),card.descriptorRevision(),card.evidenceCount(),card.generation(),
            selected.fingerprint().equals(version.get().fingerprint())?"current":"stale");
        } catch(WikiException e) {
          card=new WikiResponse.Card(card.id(),card.title(),card.kind(),card.scope(),card.parentId(),card.descriptorRevision(),card.evidenceCount(),card.generation(),"needs-check");
        }
      }
      var v=version.orElse(null);
      return new WikiResponse.Page(state(w,p),card,v==null?null:v.body(),v==null?null:v.id(),evidence,
        w.links(p,page.id()).stream().map(l->new WikiResponse.Link(l.id(),l.title(),l.kind())).toList(),job(w.latestJob(p,page.id()).orElse(null)),
        total,v==null?Math.max(0,total-evidence.size()):v.bundle().omittedSources(),added,removed,
        v!=null && !v.bundle().rendering().equals(rendering(p)));
    });
  }
  public WikiResponse.EvidencePage evidence(String name,String id,String cursor,int limit) {
    String p=profile(name);bound(limit,100);
    return store.readWiki(w->{
      Page page=require(w,p,w.canonical(p,id));String context=p+":evidence:"+page.id();String start=after(cursor,w.state(p).revision(),context);
      var snapshot=w.snapshot();
      var entries=w.evidenceMembers(p,page.id(),snapshot,start,limit+1).stream()
        .map(m->Map.entry(m,w.source(p,m.memoryId(),snapshot).orElseThrow())).toList();
      var slice=entries.stream().limit(limit).toList();
      return new WikiResponse.EvidencePage(slice.stream().map(e->evidence(e.getKey(),e.getValue(),e.getValue().id())).toList(),
        entries.size()>limit?Hash.hash128(context,Long.toString(w.state(p).revision()))+":"+slice.getLast().getValue().id():null);
    });
  }
  public WikiResponse.Evaluation evaluate(String name,String id,WikiEvaluateRequest request) {
    String p=profile(name);WikiProperties cfg=settings(p);
    if(!cfg.enabled()) throw new WikiException(409,"wiki_disabled");
    if(request==null || request.expectedDescriptorRevision()==null || !List.of("initial","refresh","retry").contains(Objects.toString(request.intent(),"")))
      throw new WikiException(400,"invalid_evaluation");
    return store.writeWiki(w->{
      Page page=require(w,p,w.canonical(p,id));
      if(page.revision()!=request.expectedDescriptorRevision() || !Objects.equals(w.version(p,page.currentVersion()).map(Version::id).orElse(null),request.expectedVersionId())) throw new WikiException(409,"page_changed");
      if(page.kind().equals("directory")) return new WikiResponse.Evaluation("evidence-only","directory",null,null);
      var previous=w.version(p,page.currentVersion());
      if(request.intent().equals("initial") && previous.isPresent()) return new WikiResponse.Evaluation("ready",null,null,previous.get().id());
      Bundle bundle=WikiEvidenceSelector.select(w,p,page,cfg,rendering(p));
      if(bundle.evidence().size()<2) return new WikiResponse.Evaluation("evidence-only","insufficient_sources",null,null);
      if(previous.isPresent() && previous.get().fingerprint().equals(bundle.fingerprint())) return new WikiResponse.Evaluation("ready",null,null,previous.get().id());
      Job job=w.enqueue(p,bundle,cfg.queueLimit());
      return new WikiResponse.Evaluation(job.status(),null,job(job),null);
    });
  }
  /** Article-bearing pages: active topic and entity pages that are not suppressed. */
  private static boolean articlePage(Page page) {
    return !page.kind().equals("directory") && page.lifecycle().equals("active") && !page.suppressed();
  }
  private List<Page> allPages(WikiRepository w,String p) {
    List<Page> all=new ArrayList<>();String after="";
    while(true) {
      var batch=w.pages(p,after,500);all.addAll(batch);
      if(batch.size()<500) return all;
      after=batch.getLast().id();
    }
  }
  public WikiResponse.Status status(String name) {
    String p=profile(name);
    return store.readWiki(w->{
      var state=state(w,p);
      int total=0,ready=0,stale=0,queued=0,generating=0,failed=0;
      for(Page page:allPages(w,p)) {
        if(!articlePage(page)) continue;
        total++;
        var version=w.version(p,page.currentVersion());
        if(version.isPresent()) { ready++; if(version.get().bundle().scopeRevision()!=page.scopeRevision()) stale++; }
        var job=w.latestJob(p,page.id()).map(Job::status).orElse("");
        if(job.equals("queued")) queued++; else if(job.equals("generating")) generating++; else if(job.equals("failed")) failed++;
      }
      int unassigned=w.anchor(p,"directory:Unassigned Evidence").map(a->w.evidenceCount(p,a.id(),w.snapshot())).orElse(0);
      return new WikiResponse.Status(state,Math.max(0,state.corpusWatermark()-state.projectionWatermark()),unassigned,total,ready,total-ready,stale,
        queued,generating,failed);
    });
  }
  /** Pages a wiki-wide build should evaluate: those without an article, plus stale ones when asked. */
  public List<String> buildPlan(String profileId,boolean includeStale) {
    return store.readWiki(w->allPages(w,profileId).stream().filter(WikiService::articlePage).filter(page->{
      var version=w.version(profileId,page.currentVersion());
      return version.isEmpty() || includeStale && version.get().bundle().scopeRevision()!=page.scopeRevision();
    }).map(Page::id).toList());
  }
  /**
   * Evaluates one page for a build, against the page as it is now rather than a version the caller
   * saw: the same selection and queueing as {@link #evaluate}, without the optimistic-concurrency check.
   */
  public WikiResponse.Evaluation evaluateForBuild(String profileId,String pageId,String intent) {
    WikiProperties cfg=settings(profileId);
    return store.writeWiki(w->{
      Page page=w.page(profileId,pageId).orElse(null);
      if(page==null || !articlePage(page)) return new WikiResponse.Evaluation("skipped","page_unavailable",null,null);
      var previous=w.version(profileId,page.currentVersion());
      if(intent.equals("initial") && previous.isPresent()) return new WikiResponse.Evaluation("ready",null,null,previous.get().id());
      Bundle bundle=WikiEvidenceSelector.select(w,profileId,page,cfg,rendering(profileId));
      if(bundle.evidence().size()<2) return new WikiResponse.Evaluation("evidence-only","insufficient_sources",null,null);
      if(previous.isPresent() && previous.get().fingerprint().equals(bundle.fingerprint())) return new WikiResponse.Evaluation("ready",null,null,previous.get().id());
      Job job=w.enqueue(profileId,bundle,cfg.queueLimit());
      return new WikiResponse.Evaluation(job.status(),null,job(job),null);
    });
  }
  /** Projection has caught up with the corpus under the current policy. */
  public boolean projectionReady(String profileId) {
    return store.readWiki(w->state(w,profileId).initialized() && w.state(profileId).checkpoint()==w.watermark(profileId));
  }
  public long[] projectionProgress(String profileId) {
    return store.readWiki(w->new long[]{w.state(profileId).checkpoint(),w.watermark(profileId)});
  }
  public String jobStatus(String profileId,String jobId) {
    return store.readWiki(w->w.job(profileId,jobId).map(Job::status).orElse("obsolete"));
  }
  /** Withdraws jobs a cancelled build queued but no worker has started. */
  public void withdrawQueued(String profileId,List<String> jobIds) {
    store.writeWiki(w->{
      for(String id:jobIds) w.job(profileId,id).filter(j->j.status().equals("queued")).ifPresent(j->w.finish(j,"obsolete","cancelled",null));
      return null;
    });
  }
  public WikiResponse.Job job(String name,String id) {
    String p=profile(name);return store.readWiki(w->job(w.job(p,id).orElseThrow(()->new WikiException(404,"job_not_found"))));
  }
  public WikiResponse.Search search(String name,String query,String mode,int limit) {
    String p=profile(name);bound(limit,50);
    if(query==null || query.isBlank() || query.length()>1000 || !List.of("lexical","semantic").contains(mode)) throw new WikiException(400,"invalid_search");
    float[] embedding=null;boolean degraded=false;
    if(mode.equals("semantic")) {
      var usage=new InferenceUsageAccumulator();
      try(var ignored=InferenceUsageSink.bind(usage)) { embedding=models.embed(query); }
      catch(RuntimeException e) { degraded=true; }
      finally { if(store.findProfile(name).isPresent()) store.recordInferenceUsage(p,usage.snapshot()); }
    }
    float[] vector=embedding;boolean fallback=degraded;
    return store.readWiki(w->{
      var snapshot=w.snapshot();String match=WikiText.match(query);
      if(match.isBlank()) return new WikiResponse.Search(state(w,p),List.of(),List.of(),fallback,false);
      var descriptorIds=w.searchPages(p,match,100);var sources=w.searchSources(p,query,vector,snapshot,100);
      Map<String,Double> scores=new HashMap<>();Map<String,Integer> hitCount=new HashMap<>();Map<String,String> excerpts=new HashMap<>();
      for(int i=0;i<descriptorIds.size();i++) scores.put(descriptorIds.get(i),1.0/(61+i));
      var unassigned=new ArrayList<WikiResponse.Evidence>();
      for(int i=0;i<sources.size();i++) {
        Source source=sources.get(i);var ids=w.placements(p,source.id());boolean assigned=false;
        Set<String> mapped=new HashSet<>();
        for(String id:ids) {
          String canonical=w.canonical(p,id);Page page=w.page(p,canonical).orElse(null);
          if(!mapped.add(canonical)) continue;
          if(page==null || page.kind().equals("directory") || page.suppressed() || !page.lifecycle().equals("active")) continue;
          assigned=true;int count=hitCount.getOrDefault(canonical,0);hitCount.put(canonical,count+1);
          excerpts.putIfAbsent(canonical,source.content().substring(0,Math.min(240,source.content().length())));
          if(count<3) scores.merge(canonical,1.0/((61+i)*(1<<count)),Double::sum);
        }
        if(!assigned && unassigned.size()<limit) unassigned.add(evidence(new Member(source.id(),"unassigned",1),source,source.id()));
      }
      String literal=query.strip().toLowerCase(Locale.ROOT);
      List<Page> pages=scores.keySet().stream().map(id->w.page(p,id)).flatMap(Optional::stream).toList();
      Comparator<Page> order=Comparator.<Page>comparingInt(page->page.title().equalsIgnoreCase(literal) || page.anchor().equalsIgnoreCase("topic:"+literal)?0:1)
        .thenComparing(Comparator.<Page>comparingDouble(page->scores.get(page.id())).reversed()).thenComparing(Page::id);
      var results=new ArrayList<WikiResponse.Card>();var remaining=new ArrayList<>(pages);
      while(!remaining.isEmpty() && results.size()<limit) {
        remaining.sort(order);Page selected=remaining.removeFirst();results.add(card(w,p,selected).withExcerpt(excerpts.get(selected.id())));
        Set<String> selectedIds=new HashSet<>();w.members(p,selected.id()).forEach(m->selectedIds.add(m.memoryId()));
        for(Page candidate:remaining) {Set<String> candidateIds=new HashSet<>();w.members(p,candidate.id()).forEach(m->candidateIds.add(m.memoryId()));
          if(dev.alvo.pieria.tools.TextSimilarity.jaccard(selectedIds,candidateIds)>.80) scores.computeIfPresent(candidate.id(),(k,v)->v*.5);
        }
      }
      return new WikiResponse.Search(state(w,p),results,unassigned,fallback,descriptorIds.size()==100 || sources.size()==100 || pages.size()>limit);
    });
  }
}
