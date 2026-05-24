package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Small deterministic store for fixture evaluation. It implements the ingestion and retrieval
 * surface used by the harness and keeps all state in memory.
 */
final class InMemoryEvaluationMemoryStore implements MemoryStore {

	private final Map<String, Profile> profilesByName = new LinkedHashMap<>();
	private final Map<String, List<Message>> messagesByProfile = new LinkedHashMap<>();
	private final Map<String, Memory> memoriesById = new LinkedHashMap<>();
	private final Map<String, List<String>> memoryIdsByProfile = new LinkedHashMap<>();
	private int profileSequence;
	private int clockSequence;

	@Override
	public Profile getOrCreateProfile(String name) {
		return profilesByName.computeIfAbsent(name, ignored -> {
			String id = "eval-profile-" + (++profileSequence);
			return new Profile(id, name, tick());
		});
	}

	@Override
	public Optional<Profile> findProfile(String name) {
		return Optional.ofNullable(profilesByName.get(name));
	}

	@Override
	public void insertMessages(String profileId, String sessionId, List<Message> messages) {
		List<Message> stored = messagesByProfile.computeIfAbsent(profileId, ignored -> new ArrayList<>());
		for (Message message : messages == null ? List.<Message>of() : messages) {
			String id = message.id() == null
				? ContentId.forMessage(sessionId, message.role(), message.content())
				: message.id();
			stored.add(new Message(id, sessionId, message.role(), message.content(),
				message.createdAt() == null ? tick() : message.createdAt()));
		}
	}

	@Override
	public Memory insertMemory(String profileId, Memory memory) {
		Memory stored = withStoreFields(memory);
		memoriesById.putIfAbsent(stored.id(), stored);
		List<String> ids = memoryIdsByProfile.computeIfAbsent(profileId, ignored -> new ArrayList<>());
		if (!ids.contains(stored.id())) {
			ids.add(stored.id());
		}
		return memoriesById.get(stored.id());
	}

	@Override
	public StoreOutcome store(String profileId, Memory memory) {
		String supersededId = null;
		if (memory.topicKey() != null && (memory.type() == MemoryType.FACT || memory.type() == MemoryType.INSTRUCTION)) {
			for (String id : memoryIdsByProfile.getOrDefault(profileId, List.of())) {
				Memory existing = memoriesById.get(id);
				if (existing != null && !existing.superseded()
					&& existing.type() == memory.type()
					&& memory.topicKey().equals(existing.topicKey())) {
					supersededId = existing.id();
					memoriesById.put(existing.id(), new Memory(existing.id(), existing.sessionId(), existing.type(),
						existing.content(), existing.topicKey(), existing.supersedes(), true, existing.payload(),
						existing.embedText(), existing.createdAt()));
				}
			}
		}
		Memory candidate = new Memory(memory.id(), memory.sessionId(), memory.type(), memory.content(),
			memory.topicKey(), supersededId, memory.superseded(), memory.payload(), memory.embedText(),
			memory.createdAt());
		Memory stored = insertMemory(profileId, candidate);
		return new StoreOutcome(stored, supersededId, stored.type() != MemoryType.TASK);
	}

	@Override
	public List<Memory> searchMemoriesFts(String profileId, String matchQuery, int limit) {
		return lexicalSearch(activeMemories(profileId), matchQuery, limit);
	}

	@Override
	public List<Memory> searchMemoriesByMessageFts(String profileId, String matchQuery, int limit) {
		List<String> matchingSessions = messagesByProfile.getOrDefault(profileId, List.of()).stream()
			.filter(message -> lexicalScore(message.content(), terms(matchQuery)) > 0)
			.map(Message::sessionId)
			.distinct()
			.toList();
		return activeMemories(profileId).stream()
			.filter(memory -> matchingSessions.contains(memory.sessionId()))
			.limit(limit)
			.toList();
	}

	@Override
	public List<Memory> exactKeyLookup(String profileId, List<String> topicKeys, int limit) {
		if (topicKeys == null || topicKeys.isEmpty()) {
			return List.of();
		}
		return activeMemories(profileId).stream()
			.filter(memory -> memory.topicKey() != null && topicKeys.contains(memory.topicKey()))
			.sorted(Comparator.comparingInt(memory -> topicKeys.indexOf(memory.topicKey())))
			.limit(limit)
			.toList();
	}

	@Override
	public List<Memory> vectorSearch(String profileId, float[] queryEmbedding, int limit) {
		return List.of();
	}

	@Override
	public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter) {
		return activeMemories(profileId).stream()
			.filter(memory -> typeFilter == null || memory.type() == typeFilter)
			.filter(memory -> sessionFilter == null || sessionFilter.equals(memory.sessionId()))
			.toList();
	}

	@Override
	public boolean forgetMemory(String profileId, String memoryId) {
		Memory existing = memoriesById.get(memoryId);
		if (existing == null || existing.superseded()) {
			return false;
		}
		memoriesById.put(memoryId, new Memory(existing.id(), existing.sessionId(), existing.type(),
			existing.content(), existing.topicKey(), existing.supersedes(), true, existing.payload(),
			existing.embedText(), existing.createdAt()));
		return true;
	}

	@Override
	public List<ExportRow> exportProfile(String profileId) {
		String profileName = profilesByName.values().stream()
			.filter(profile -> profile.id().equals(profileId))
			.map(Profile::name)
			.findFirst()
			.orElse(profileId);
		return activeMemories(profileId).stream()
			.map(memory -> new ExportRow(profileName, memory))
			.toList();
	}

	@Override
	public List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit) {
		return lexicalSearch(activeMemories(profileId), query, limit).stream()
			.map(memory -> new RecallCandidate(memory, 1.0, "evaluation_lexical"))
			.toList();
	}

	private Memory withStoreFields(Memory memory) {
		String payload = memory.payload() == null ? "{}" : memory.payload();
		String id = memory.id() == null
			? ContentId.forMemory(memory.sessionId(), memory.type(), memory.content(), memory.topicKey(), payload)
			: memory.id();
		return new Memory(id, memory.sessionId(), memory.type(), memory.content(), memory.topicKey(),
			memory.supersedes(), memory.superseded(), payload, memory.embedText(),
			memory.createdAt() == null ? tick() : memory.createdAt());
	}

	private List<Memory> activeMemories(String profileId) {
		return memoryIdsByProfile.getOrDefault(profileId, List.of()).stream()
			.map(memoriesById::get)
			.filter(memory -> memory != null && !memory.superseded())
			.toList();
	}

	private static List<Memory> lexicalSearch(List<Memory> memories, String query, int limit) {
		List<String> terms = terms(query);
		if (terms.isEmpty()) {
			return List.of();
		}
		return memories.stream()
			.map(memory -> new ScoredMemory(memory, lexicalScore(memory.content(), terms)))
			.filter(scored -> scored.score() > 0)
			.sorted(Comparator.comparingInt(ScoredMemory::score).reversed()
				.thenComparing(scored -> scored.memory().createdAt(), Comparator.nullsLast(Comparator.reverseOrder())))
			.limit(limit)
			.map(ScoredMemory::memory)
			.toList();
	}

	private static int lexicalScore(String content, List<String> terms) {
		String normalized = normalize(content);
		int score = 0;
		for (String term : terms) {
			if (normalized.contains(term)) {
				score++;
			}
		}
		return score;
	}

	private static List<String> terms(String query) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		List<String> terms = new ArrayList<>();
		for (String term : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (term.length() >= 3 && !terms.contains(term)) {
				terms.add(term);
			}
		}
		return terms;
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private Instant tick() {
		return Instant.EPOCH.plusSeconds(++clockSequence);
	}

	private record ScoredMemory(Memory memory, int score) {
	}
}
