package io.github.skuirrels.traderouter.graph;

/**
 * A directed edge in the maritime network graph.
 *
 * @param targetNodeId index of the adjacent target node
 * @param weight       edge length in kilometres
 * @param passage      optional passage name, for example "suez" or "panama", or null
 */
public record GraphEdge(int targetNodeId, double weight, String passage) {
}
