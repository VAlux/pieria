package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix="pieria.wiki")
public record WikiProperties(@DefaultValue("false") boolean enabled,
    @DefaultValue("8") int batchSize, @DefaultValue("5000") long intervalMs,
    @DefaultValue("100") int queueLimit, @DefaultValue("180") int timeoutSeconds,
    @DefaultValue("2") int maxAttempts, @DefaultValue("40") int maxSources,
    @DefaultValue("8000") int inputTokens, @DefaultValue("1500") int outputTokens,
    @DefaultValue("0.80") double semanticAdmit, @DefaultValue("0.75") double semanticRetain,
    @DefaultValue("0.35") double lexicalAdmit, @DefaultValue("0.25") double lexicalRetain,
    @DefaultValue("0.20") double subjectLexical, @DefaultValue("80") int splitSources,
    @DefaultValue("16000") int splitTokens, @DefaultValue("0.80") double mergeOverlap,
    @DefaultValue("0.90") double mergeCosine, @DefaultValue("0.60") double mergeLexical,
    @DefaultValue("10") int structuralLimit) {
  public static final WikiProperties DEFAULT = new WikiProperties(false,8,5000,100,180,2,40,8000,1500,
    .80,.75,.35,.25,.20,80,16000,.80,.90,.60,10);
  private static <T> T value(T o,T base) { return o==null?base:o; }
  public WikiProperties overlay(dev.alvo.pieria.config.model.DaemonOverrides.Wiki o) {
    if(o==null) return this;
    return new WikiProperties(value(o.enabled(),enabled),batchSize,intervalMs,queueLimit,timeoutSeconds,maxAttempts,
      value(o.maxSources(),maxSources),value(o.inputTokens(),inputTokens),value(o.outputTokens(),outputTokens),
      value(o.semanticAdmit(),semanticAdmit),value(o.semanticRetain(),semanticRetain),value(o.lexicalAdmit(),lexicalAdmit),
      value(o.lexicalRetain(),lexicalRetain),value(o.subjectLexical(),subjectLexical),value(o.splitSources(),splitSources),
      value(o.splitTokens(),splitTokens),value(o.mergeOverlap(),mergeOverlap),value(o.mergeCosine(),mergeCosine),value(o.mergeLexical(),mergeLexical),structuralLimit);
  }
  public dev.alvo.pieria.config.model.DaemonOverrides.Wiki overrides() {
    return new dev.alvo.pieria.config.model.DaemonOverrides.Wiki(enabled,maxSources,inputTokens,outputTokens,
      semanticAdmit,semanticRetain,lexicalAdmit,lexicalRetain,subjectLexical,splitSources,splitTokens,mergeOverlap,mergeCosine,mergeLexical);
  }
  public WikiProperties {
    if (batchSize<1 || intervalMs<100 || queueLimit<1 || timeoutSeconds<1 || maxAttempts<1 || maxAttempts>2
        || maxSources<1 || inputTokens<256 || outputTokens<1 || splitSources<3 || splitTokens<1 || structuralLimit<1)
      throw new IllegalArgumentException("Invalid wiki budget");
    for (double value : new double[]{semanticAdmit,semanticRetain,lexicalAdmit,lexicalRetain,subjectLexical,mergeOverlap,mergeCosine,mergeLexical})
      if (!Double.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("Wiki similarity must be in [0,1]");
    if (semanticRetain>semanticAdmit || lexicalRetain>lexicalAdmit)
      throw new IllegalArgumentException("Wiki retention cannot exceed admission");
  }
}
