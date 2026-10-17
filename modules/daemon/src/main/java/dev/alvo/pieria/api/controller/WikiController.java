package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.WikiEvaluateRequest;
import dev.alvo.pieria.api.response.WikiResponse;
import dev.alvo.pieria.wiki.WikiException;
import dev.alvo.pieria.wiki.WikiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/v1/profiles/{name}/wiki")
public class WikiController {
  private final WikiService wiki;
  public WikiController(WikiService wiki) {this.wiki=wiki;}
  @ModelAttribute public void guards(HttpServletRequest req,HttpServletResponse res) {
    res.setHeader("Cache-Control","no-store");
    if(req.getParameter("asOf")!=null || req.getParameter("knownAsOf")!=null) throw new WikiException(400,"historical_wiki_unsupported");
  }
  @GetMapping({"","/"}) public WikiResponse.Directory directory(@PathVariable String name,
      @RequestParam(required=false) String parent,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit) {
    return wiki.directory(name,parent,cursor,limit);
  }
  @GetMapping("/search") public WikiResponse.Search search(@PathVariable String name,@RequestParam String q,
      @RequestParam(defaultValue="lexical") String mode,@RequestParam(defaultValue="20") int limit) {return wiki.search(name,q,mode,limit);}
  private URI pageUri(String name,String id) {
    return URI.create("/v1/profiles/"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+"/wiki/pages/"+id);
  }
  @GetMapping("/pages/{id}") public ResponseEntity<?> page(@PathVariable String name,@PathVariable String id) {
    String canonical=wiki.canonical(name,id);
    if(!canonical.equals(id)) return ResponseEntity.status(307).location(pageUri(name,canonical)).build();
    return ResponseEntity.ok(wiki.page(name,id));
  }
  @GetMapping("/pages/{id}/evidence") public WikiResponse.EvidencePage evidence(@PathVariable String name,@PathVariable String id,
      @RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit) {return wiki.evidence(name,id,cursor,limit);}
  @PostMapping("/pages/{id}/evaluate") public ResponseEntity<?> evaluate(@PathVariable String name,@PathVariable String id,@RequestBody WikiEvaluateRequest request) {
    String canonical=wiki.canonical(name,id);
    if(!canonical.equals(id)) return ResponseEntity.status(307).location(URI.create(pageUri(name,canonical)+"/evaluate")).build();
    var result=wiki.evaluate(name,id,request);
    if(result.job()==null) return ResponseEntity.ok(result);
    URI job=URI.create("/v1/profiles/"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+"/wiki/jobs/"+result.job().id());
    return ResponseEntity.accepted().location(job).body(result);
  }
  @GetMapping("/jobs/{id}") public WikiResponse.Job job(@PathVariable String name,@PathVariable String id) {return wiki.job(name,id);}
  @ExceptionHandler(WikiException.class) public ResponseEntity<?> error(WikiException e) {
    var response=ResponseEntity.status(e.status()).header("Cache-Control","no-store");
    if(e.status()==409 || e.status()==429)response.header("Retry-After","5");
    return response.body(Map.of("code",e.getMessage()));
  }
}
