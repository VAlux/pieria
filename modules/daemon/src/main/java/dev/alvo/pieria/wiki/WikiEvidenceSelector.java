package dev.alvo.pieria.wiki;

import dev.alvo.pieria.config.WikiProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.retrieval.TemporalExtractor;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.tools.PromptTemplateLoader;
import dev.alvo.pieria.tools.Tokens;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import static dev.alvo.pieria.wiki.WikiData.*;

/** Pure selection and citation validation; all inference happens after this bundle leaves storage. */
public final class WikiEvidenceSelector {
  private static final ObjectMapper JSON=new ObjectMapper();
  private WikiEvidenceSelector() {}
  public static Bundle select(WikiRepository w,String p,Page page,WikiProperties cfg,String rendering) {
    State state=w.state(p);long watermark=w.watermark(p); TemporalSnapshot snapshot=w.snapshot();
    if(!state.initialized() || !state.policy().equals(WikiProjectionService.policy(cfg)) || state.checkpoint()!=watermark || state.nextBoundary()!=null && !state.nextBoundary().isAfter(snapshot.asOf()))
      throw new WikiException(409,"projection_not_ready");
    Map<String,List<Map.Entry<Member,Source>>> grouped=new TreeMap<>();
    int total=0;
    for(Member member:w.members(p,page.id())) {
      Source source=w.source(p,member.memoryId(),snapshot).orElse(null);if(source==null) continue;total++;
      String group=source.validity().isEmpty()?source.id():source.validity().getFirst().factGroup();
      grouped.computeIfAbsent(group,k->new ArrayList<>()).add(Map.entry(member,source));
    }
    var groups=new ArrayList<>(grouped.entrySet());
    groups.sort(Comparator.<Map.Entry<String,List<Map.Entry<Member,Source>>>>comparingInt(g->
      g.getValue().stream().anyMatch(e->e.getKey().role().equals("core"))?0:1).thenComparing(Map.Entry::getKey));
    List<Link> links=w.links(p,page.id());
    List<Evidence> selected=new ArrayList<>();List<Memory> temporalInputs=new ArrayList<>();
    Bundle bundle=assemble(p,page,rendering,snapshot,watermark,selected,links,temporalInputs,total,cfg,w.nextBoundary(p,snapshot.asOf()));
    if(Tokens.estimate(prompt(bundle))>cfg.inputTokens()) throw new WikiException(400,"input_budget_exceeded");
    for(var group:groups) {
      if(selected.size()+group.getValue().size()>cfg.maxSources()) continue;
      var evidence=new ArrayList<>(selected);var temporal=new ArrayList<>(temporalInputs);
      for(var entry:group.getValue()) {
        Source src=entry.getValue();
        evidence.add(new Evidence("E"+(evidence.size()+1),src.id(),src.content(),src.type(),entry.getKey().role(),src.validity(),false));
        String payload=src.validity().isEmpty()?src.payload():src.validity().getFirst().sourcePayload();
        temporal.add(Memory.of(MemoryType.valueOf(src.type().toUpperCase(Locale.ROOT)),src.content(),null,src.topicKey(),payload));
      }
      Bundle candidate=assemble(p,page,rendering,snapshot,watermark,evidence,links,temporal,total,cfg,bundle.nextBoundary());
      // Conflicting assertions are admitted together. A singleton may use an explicitly marked excerpt.
      while(Tokens.estimate(prompt(candidate))>cfg.inputTokens() && group.getValue().size()==1 && evidence.getLast().content().length()>256) {
        Evidence last=evidence.removeLast();String content=last.content().substring(0,Math.max(256,last.content().length()/2));
        evidence.add(new Evidence(last.ref(),last.memoryId(),content,last.type(),last.role(),last.validity(),true));
        Source source=group.getValue().getFirst().getValue();temporal.removeLast();
        String payload=source.validity().isEmpty()?source.payload():source.validity().getFirst().sourcePayload();
        temporal.add(Memory.of(MemoryType.valueOf(source.type().toUpperCase(Locale.ROOT)),content,null,source.topicKey(),payload));
        candidate=assemble(p,page,rendering,snapshot,watermark,evidence,links,temporal,total,cfg,bundle.nextBoundary());
      }
      if(Tokens.estimate(prompt(candidate))<=cfg.inputTokens()) {selected=evidence;temporalInputs=temporal;bundle=candidate;}
    }
    List<ClusterClaim> claims=new ArrayList<>(w.clusterClaims(p,page,snapshot,selected));
    do {
      bundle=assemble(p,page,rendering,snapshot,watermark,selected,links,temporalInputs,total,cfg,bundle.nextBoundary(),claims);
      if(Tokens.estimate(prompt(bundle))<=cfg.inputTokens() || claims.isEmpty()) break;
      claims.removeLast();
    } while(true);
    if(Tokens.estimate(prompt(bundle))>cfg.inputTokens()) throw new WikiException(400,"input_budget_exceeded");
    return bundle;
  }
  private static Bundle assemble(String p,Page page,String rendering,TemporalSnapshot snapshot,long watermark,
      List<Evidence> evidence,List<Link> links,List<Memory> temporalInputs,int total,WikiProperties cfg,java.time.Instant boundary) {
    return assemble(p,page,rendering,snapshot,watermark,evidence,links,temporalInputs,total,cfg,boundary,List.of());
  }
  private static Bundle assemble(String p,Page page,String rendering,TemporalSnapshot snapshot,long watermark,
      List<Evidence> evidence,List<Link> links,List<Memory> temporalInputs,int total,WikiProperties cfg,java.time.Instant boundary,List<ClusterClaim> claims) {
    List<String> temporal=new TemporalExtractor().extract("",snapshot.asOf(),temporalInputs).stream().map(f->f.render()).toList();
    String fingerprint=Hash.hash128(p,page.id(),page.title(),Long.toString(page.scopeRevision()),JSON.writeValueAsString(evidence),
      Integer.toString(total),JSON.writeValueAsString(temporal),JSON.writeValueAsString(links.stream().map(Link::id).sorted().toList()),
      JSON.writeValueAsString(claims),rendering,"wiki-selection-2");
    return new Bundle(page.id(),page.title(),page.scopeRevision(),page.revision(),fingerprint,rendering,snapshot,watermark,
      List.copyOf(evidence),links,temporal,total,total-evidence.size(),cfg.outputTokens(),boundary,List.copyOf(claims));
  }
  public static String prompt(Bundle b) {
    Map<String,Object> input=new LinkedHashMap<>();
    input.put("title",b.title());input.put("evidence",b.evidence());input.put("temporalFacts",b.temporalFacts());
    input.put("validatedClusterClaims",b.clusterClaims()==null?List.of():b.clusterClaims());
    input.put("allowedPageIds",b.links().stream().map(Link::id).toList());
    input.put("omittedSources",b.omittedSources());input.put("outputTokens",b.maxOutputTokens());
    return PromptTemplateLoader.render("synthesize-wiki",Map.of("bundle",JSON.writeValueAsString(input)));
  }
  public static String validate(String raw,Bundle b) {
    try {
      if(raw==null || Tokens.estimate(raw)>b.maxOutputTokens()*2L) throw new IllegalArgumentException();
      var root=JSON.readTree(raw);var sections=root.get("sections");
      if(sections==null || !sections.isArray() || sections.isEmpty() || sections.size()>12) throw new IllegalArgumentException();
      Set<String> refs=new HashSet<>();b.evidence().forEach(e->refs.add(e.ref()));
      Set<String> links=new HashSet<>();b.links().forEach(l->links.add(l.id()));
      var result=new StringBuilder();int paragraphs=0;
      for(var section:sections) {
        String title=section.path("title").asText("");
        if(title.isBlank() || title.length()>120 || title.matches("(?s).*[<>\\[\\]\\n].*")) throw new IllegalArgumentException();
        result.append("## ").append(title).append("\n\n");
        var items=section.get("paragraphs");if(items==null || !items.isArray() || items.isEmpty()) throw new IllegalArgumentException();
        for(var item:items) {
          String text=item.path("text").asText("");var cites=item.get("citations");
          if(text.isBlank() || text.contains("<") || text.contains(">") || text.contains("![") || text.matches("(?s).*\\]\\s*\\(.*")
              || text.contains("\n") || text.matches("(?is).*(https?://|www\\.|mailto:|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}).*")
              || text.matches("(?s).*\\[[^\\]]+\\]:.*") || cites==null || !cites.isArray() || cites.isEmpty()) throw new IllegalArgumentException();
          var matcher=Pattern.compile("\\[\\[([^\\]]+)]]").matcher(text);StringBuffer clean=new StringBuffer();
          while(matcher.find()) {
            String id=matcher.group(1);if(!links.contains(id)) throw new IllegalArgumentException();
            matcher.appendReplacement(clean,java.util.regex.Matcher.quoteReplacement("[Related page](#wiki-"+id+")"));
          }
          matcher.appendTail(clean);result.append(clean);
          for(var cite:cites) { String ref=cite.asText();if(!refs.contains(ref)) throw new IllegalArgumentException();result.append(" [").append(ref).append("](#source-").append(ref).append(")"); }
          result.append("\n\n");paragraphs++;
        }
      }
      if(paragraphs==0 || Tokens.estimate(result.toString())>b.maxOutputTokens()) throw new IllegalArgumentException();
      return result.toString();
    } catch(RuntimeException e) { throw new WikiException(400,"invalid_article"); }
  }
}
