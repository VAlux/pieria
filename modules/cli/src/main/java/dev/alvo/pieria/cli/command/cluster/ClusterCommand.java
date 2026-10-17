package dev.alvo.pieria.cli.command.cluster;

import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.ProfileClient;
import dev.alvo.pieria.client.exception.DaemonClientException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Bounded cluster inspection and manual controls. All writes carry expected revisions. */
@Command(name="cluster",mixinStandardHelpOptions=true,
  description="Inspect and manage nested knowledge clusters. Actions: list, show, parents, members, descendants, originals, synthesis, revisions, create, attach, detach, rename, dissolve, restore, regenerate, export, runs, run, events, audit, start, pause, resume, cancel, schedule, set-schedule.")
public final class ClusterCommand implements Callable<Integer> {
  @Parameters(index="0",description="Action") String action;
  @Parameters(index="1",arity="0..1",description="Cluster or run ID") String id;
  @Option(names="--profile") String profile;
  @Option(names="--daemon-url") String daemonUrl;
  @Option(names="--title") String title;
  @Option(names="--scope") String scope;
  @Option(names="--member",split=",") List<String> member;
  @Option(names="--revision") Integer revision;
  @Option(names="--source-revision") Integer sourceRevision;
  @Option(names="--query") String query;
  @Option(names="--limit",defaultValue="30") int limit;
  @Option(names="--enabled") Boolean enabled;
  @Option(names="--time",defaultValue="02:00") String localTime;
  @Option(names="--timezone") String timezone;

  private String needId() {
    if(id==null || id.isBlank()) throw new IllegalArgumentException("cluster or run ID required");
    return id;
  }
  private int needRevision() {
    if(revision==null) throw new IllegalArgumentException("--revision required");
    return revision;
  }
  private String need(String value,String option) {
    if(value==null || value.isBlank()) throw new IllegalArgumentException(option+" required");
    return value;
  }
  private Map<String,String> member(String value) {
    int colon=value.indexOf(':');
    if(colon<1 || colon==value.length()-1) throw new IllegalArgumentException("member must be memory:ID or cluster:ID");
    String kind=value.substring(0,colon),key=value.substring(colon+1);
    if(!kind.equals("memory") && !kind.equals("cluster")) throw new IllegalArgumentException("member kind must be memory or cluster");
    return Map.of("kind",kind,"id",key);
  }
  private String oneMember() {
    if(member==null || member.size()!=1) throw new IllegalArgumentException("exactly one --member required");
    return member.getFirst();
  }

  @Override public Integer call() {
    try {
      String name=profile==null?ProfileResolver.create(Path.of("").toAbsolutePath()).resolve():ProfileResolver.normalize(profile);
      ProfileClient client=new ProfileClient(DaemonUrls.resolve(daemonUrl),BuildInfo.clientIdentity());
      String result=switch(action) {
        case "list" -> client.clusterGet(name,"/clusters?limit="+limit+(query==null?"":"&q="+java.net.URLEncoder.encode(query,java.nio.charset.StandardCharsets.UTF_8)));
        case "show" -> client.clusterGet(name,"/clusters/"+needId());
        case "parents" -> client.clusterGet(name,"/clusters/"+needId()+"/parents");
        case "members" -> client.clusterGet(name,"/clusters/"+needId()+"/members");
        case "descendants" -> client.clusterGet(name,"/clusters/"+needId()+"/descendants?limit="+limit);
        case "originals" -> client.clusterGet(name,"/clusters/"+needId()+"/originals?limit="+limit);
        case "synthesis" -> client.clusterGet(name,"/clusters/"+needId()+"/synthesis");
        case "revisions" -> client.clusterGet(name,"/clusters/"+needId()+"/revisions");
        case "export" -> client.clusterGet(name,"/clusters/export");
        case "create" -> client.clusterPost(name,"/clusters",Map.of("title",need(title,"--title"),
          "scope",need(scope,"--scope"),"members",member==null?List.of():member.stream().map(this::member).toList()));
        case "attach" -> client.clusterPost(name,"/clusters/"+needId()+"/members",
          Map.of("expectedRevision",needRevision(),"member",member(oneMember())));
        case "detach" -> client.clusterPost(name,"/clusters/"+needId()+"/members/detach",
          Map.of("expectedRevision",needRevision(),"member",member(oneMember())));
        case "rename" -> client.clusterPost(name,"/clusters/"+needId()+"/rename",
          Map.of("expectedRevision",needRevision(),"title",need(title,"--title"),"scope",need(scope,"--scope")));
        case "dissolve" -> client.clusterPost(name,"/clusters/"+needId()+"/dissolve",Map.of("expectedRevision",needRevision()));
        case "restore" -> client.clusterPost(name,"/clusters/"+needId()+"/restore",
          Map.of("expectedRevision",needRevision(),"sourceRevision",sourceRevision==null?1:sourceRevision));
        case "regenerate" -> client.clusterPost(name,"/clusters/"+needId()+"/regenerate",Map.of("expectedRevision",needRevision()));
        case "runs" -> client.clusterGet(name,"/clustering/runs?limit="+limit);
        case "run" -> client.clusterGet(name,"/clustering/runs/"+needId());
        case "events" -> client.clusterGet(name,"/clustering/runs/"+needId()+"/events?limit="+limit);
        case "audit" -> client.clusterGet(name,"/clustering/runs/"+needId()+"/audit?limit="+limit);
        case "start" -> client.clusterPost(name,"/clustering/runs",Map.of());
        case "pause","resume","cancel" -> client.clusterPost(name,"/clustering/runs/"+needId()+"/"+action,Map.of());
        case "schedule" -> client.clusterGet(name,"/clustering/schedule");
        case "set-schedule" -> client.clusterPut(name,"/clustering/schedule",Map.of("enabled",enabled!=null && enabled,
          "localTime",localTime,"timezone",timezone==null?java.time.ZoneId.systemDefault().getId():timezone));
        default -> throw new IllegalArgumentException("unknown cluster action: "+action);
      };
      System.out.println(result);
      return 0;
    } catch(DaemonUnavailableException e) {
      System.err.println("Pieria daemon is unreachable: "+e.getMessage());return 3;
    } catch(DaemonClientException | IllegalArgumentException e) {
      System.err.println(e.getMessage());return 1;
    }
  }
}
