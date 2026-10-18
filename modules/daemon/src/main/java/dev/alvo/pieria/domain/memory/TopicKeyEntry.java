package dev.alvo.pieria.domain.memory;

/**
 * The memory currently visible under a topic key for one type — what a new memory with the same
 * type and key would replace.
 */
public record TopicKeyEntry(String topicKey, Memory memory) {
}
