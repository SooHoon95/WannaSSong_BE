package io.anick.wannassong.fallback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.jukebox.StateStore;
import io.anick.wannassong.jukebox.Track;
import io.anick.wannassong.youtube.YouTubeClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * data/fallback.txt 파싱 + 소스 해석 + 카테고리별 곡 누적(fallback-pool.json).
 * 6시간마다 다시 로드해 chart/search 결과가 날마다 불어나게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackService {

	private static final int POOL_MAX_PER_CATEGORY = 500;

	private static final long SEARCH_CACHE_MS = 24 * 60 * 60 * 1000L;

	private static final Pattern SECTION = Pattern.compile("^\\[(.+)]$");

	private final WannaSongProperties props;

	private final StateStore store;

	private final YouTubeClient youtube;

	private final ApplicationEventPublisher events;

	/** 카테고리명 -> 곡 목록. */
	private volatile Map<String, List<Track>> pools = new LinkedHashMap<>();

	/** 검색어 -> 결과 (100유닛/회라 24시간 캐시). */
	private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();

	public Map<String, List<Track>> pools() {
		return pools;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedRate = 6 * 60 * 60 * 1000L, initialDelay = 6 * 60 * 60 * 1000L)
	public void reload() {
		Map<String, List<FallbackSource>> cats = parse();
		Map<String, List<Track>> saved = store.pool();
		Map<String, List<Track>> next = new LinkedHashMap<>();
		Map<String, List<Track>> toSave = new LinkedHashMap<>();
		for (Map.Entry<String, List<FallbackSource>> e : cats.entrySet()) {
			List<Track> fresh = resolve(e.getValue());
			// 동적 소스(검색·차트·재생목록)가 있는 카테고리만 누적. 링크만 있는 카테고리는 파일 내용 그대로.
			boolean dynamic = e.getValue().stream().anyMatch(s -> !"video".equals(s.type()));
			List<Track> tracks = dynamic ? merge(fresh, saved.getOrDefault(e.getKey(), List.of())) : fresh;
			next.put(e.getKey(), tracks);
			if (dynamic) {
				toSave.put(e.getKey(), tracks);
			}
			log.info("[fallback] [{}] {}곡{}", e.getKey(), tracks.size(),
					dynamic ? " (이번 갱신 " + fresh.size() + "곡)" : "");
		}
		pools = next;
		if (!toSave.isEmpty()) {
			store.writePool(toSave);
		}
		events.publishEvent(new FallbackReloadedEvent());
	}

	/**
	 * fallback.txt 형식:
	 * <pre>
	 * [카테고리]           섹션. 없으면 "기본"
	 * https://youtu.be/…  영상 (키 불필요)
	 * playlist:PLxxxx     재생목록 (키 필요)
	 * search:검색어        검색 50곡 (키 필요, 24시간 캐시)
	 * chart:KR            그 나라 인기 음악 50곡 (키 필요)
	 * </pre>
	 */
	Map<String, List<FallbackSource>> parse() {
		Map<String, List<FallbackSource>> cats = new LinkedHashMap<>();
		String cur = "기본";
		for (String raw : lines()) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			Matcher sec = SECTION.matcher(line);
			if (sec.matches()) {
				String name = sec.group(1).trim();
				cur = name.isEmpty() ? "기본" : name.substring(0, Math.min(name.length(), 20));
				cats.computeIfAbsent(cur, k -> new ArrayList<>());
				continue;
			}
			FallbackSource src = source(line);
			if (src != null) {
				cats.computeIfAbsent(cur, k -> new ArrayList<>()).add(src);
			}
		}
		if (!props.getFallbackPlaylist().isBlank()) {
			String name = cats.isEmpty() ? "기본" : cats.keySet().iterator().next();
			cats.computeIfAbsent(name, k -> new ArrayList<>())
					.add(new FallbackSource("playlist", props.getFallbackPlaylist()));
		}
		return cats;
	}

	private FallbackSource source(String line) {
		String lower = line.toLowerCase();
		if (lower.startsWith("playlist:")) {
			return new FallbackSource("playlist", line.substring(9).trim());
		}
		if (lower.startsWith("search:")) {
			return new FallbackSource("search", line.substring(7).trim());
		}
		if (lower.equals("chart") || lower.startsWith("chart:")) {
			String[] parts = line.split(":", 2);
			String region = parts.length > 1 ? parts[1].trim().toUpperCase() : "";
			return new FallbackSource("chart", region.isEmpty() ? "KR" : region);
		}
		String id = youtube.parseVideoId(line);
		return id == null ? null : new FallbackSource("video", id);
	}

	private List<String> lines() {
		Path file = store.fallbackFile();
		try {
			if (Files.exists(file)) {
				return Files.readAllLines(file);
			}
			ClassPathResource bundled = new ClassPathResource("fallback.txt");
			if (bundled.exists()) {
				return new String(bundled.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
			}
		}
		catch (Exception e) {
			log.warn("[fallback] 파일 읽기 실패 {}: {}", file, e.getMessage());
			return List.of();
		}
		log.warn("[fallback] {} 없음 — 자동 재생 목록 비어 있음", file);
		return List.of();
	}

	private List<Track> resolve(List<FallbackSource> sources) {
		Set<String> seen = new LinkedHashSet<>();
		List<Track> out = new ArrayList<>();
		Consumer<Track> push = t -> {
			if (t != null && seen.add(t.videoId())) {
				out.add(t);
			}
		};
		for (FallbackSource src : sources) {
			try {
				switch (src.type()) {
					case "video" -> push.accept(youtube.oembed(src.value()));
					case "playlist" -> withKey(src, () -> youtube.playlistItems(src.value()).forEach(push));
					case "chart" -> withKey(src, () -> youtube.popularMusic(src.value()).forEach(push));
					case "search" -> withKey(src, () -> cachedSearch(src.value()).forEach(push));
					default -> log.warn("[fallback] 알 수 없는 소스 {}", src);
				}
			}
			catch (Exception e) {
				log.warn("[fallback] {} {} 실패: {}", src.type(), src.value(), e.getMessage());
			}
		}
		return out;
	}

	private void withKey(FallbackSource src, Runnable action) {
		if (!youtube.hasApiKey()) {
			log.warn("[fallback] {}:{} 는 YT_API_KEY 가 필요해 건너뜀", src.type(), src.value());
			return;
		}
		action.run();
	}

	private List<Track> cachedSearch(String query) {
		CachedSearch cached = searchCache.get(query);
		if (cached != null && System.currentTimeMillis() - cached.at() < SEARCH_CACHE_MS) {
			return cached.tracks();
		}
		try {
			List<Track> tracks = youtube.search(query, 50);
			searchCache.put(query, new CachedSearch(System.currentTimeMillis(), tracks));
			return tracks;
		}
		catch (RuntimeException e) {
			if (cached != null) {
				return cached.tracks(); // 한도 초과 시 예전 결과라도 쓴다
			}
			throw e;
		}
	}

	/** 오늘 결과에서 빠진 곡도 카테고리당 500곡까지 남겨 목록이 날마다 불어나게 한다. */
	private List<Track> merge(List<Track> fresh, List<Track> saved) {
		Set<String> seen = new LinkedHashSet<>();
		List<Track> merged = new ArrayList<>();
		for (List<Track> list : List.of(fresh, saved)) {
			for (Track t : list) {
				if (t == null || t.videoId() == null || !seen.add(t.videoId())) {
					continue;
				}
				merged.add(t);
				if (merged.size() >= POOL_MAX_PER_CATEGORY) {
					return merged;
				}
			}
		}
		return merged;
	}

	private record CachedSearch(long at, List<Track> tracks) {
	}
}
