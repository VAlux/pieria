package dev.alvo.pieria.retrieval.model;

/**
 * One ephemeral code-graph fact carried alongside (not fused with) the memory candidates: a single
 * {@code code_edges} row rendered against its endpoint symbols. Produced by the code-graph channel
 * from the query's seed symbols and injected into synthesis as first-class evidence — it is never
 * stored as a memory and never participates in RRF fusion.
 *
 * @param src        source symbol qualified name
 * @param srcPath    source symbol repo-relative path
 * @param relation   wire form of the relation, e.g. {@code "calls"}
 * @param dst        target qualified name, or the raw referenced name when unresolved
 * @param dstPath    target repo-relative path; null when the target is unresolved
 * @param confidence wire form of the edge confidence, e.g. {@code "resolved"}
 */
public record GraphEvidence(
  String src,
  String srcPath,
  String relation,
  String dst,
  String dstPath,
  String confidence) {

  /** e.g. {@code "JGPT#main (app/src/main/java/dev/alvo/JGPT.java) calls Model#gpt (app/src/main/java/dev/alvo/model/Model.java) [resolved]"}. */
  public String render() {
    String verb = relation == null ? "relates to" : relation.replace('-', ' ');
    String target = dstPath == null ? dst : dst + " (" + dstPath + ")";
    return src + " (" + srcPath + ") " + verb + " " + target + " [" + confidence + "]";
  }
}
