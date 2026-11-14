package dev.alvo.pieria.harvesters.incremental;

import java.time.Instant;

/**
 * One item as a change listing shows it.
 *
 * @param version   identifies one state of the item, so an unchanged item is not reported twice
 * @param changedAt when that state began, by the source's clock
 * @param data      whatever the feed needs to load the item
 */
public record Change<T>(String id, String version, Instant changedAt, T data) {
}
