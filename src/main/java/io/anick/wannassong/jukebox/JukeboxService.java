package io.anick.wannassong.jukebox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.fallback.FallbackReloadedEvent;
import io.anick.wannassong.fallback.FallbackService;
import io.anick.wannassong.youtube.YouTubeClient;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * server.js 의 상태 로직 이식: 대기열, 쿨다운, 히스토리, 자동 재생 선곡, 스피커 단일 선출.
 * ponytail: 인스턴스 하나 + 굵은 락 하나. Node 가 단일 스레드였던 것과 같은 보장을 준다.
 * 부하가 문제되면 큐/플레이백을 나눠 잠글 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JukeboxService {

	private static final int HISTORY_MAX = 200;

	private static final int HISTORY_PUBLIC = 30;

	private final WannaSongProperties props;

	private final StateStore store;

	private final FallbackService fallback;

	private final YouTubeClient youtube;

	private final SocketIOServer server;

	private final Random random = new Random();

	/** 임베드 차단·삭제로 재생 실패한 영상 (자동 재생에서 제외). */
	private final Set<String> failedVideoIds = ConcurrentHashMap.newKeySet();

	/** "artist|title" -> 트랙 (YouTube 검색 100유닛 절약). */
	private final Map<String, Track> searchCache = new ConcurrentHashMap<>();

	private volatile Playback playback = Playback.idle();

	private volatile UUID speakerSessionId;

	// ---------- 상태 조회 ----------

	public boolean speakerOnline() {
		UUID id = speakerSessionId;
		return id != null && server.getClient(id) != null;
	}

	public boolean isSpeaker(SocketIOClient client) {
		return client.getSessionId().equals(speakerSessionId);
	}

	public Map<String, Object> publicState() {
		JukeboxState s = store.state();
		synchronized (this) {
			List<Map<String, Object>> categories = new ArrayList<>();
			fallback.pools().forEach((name, tracks) -> categories.add(Map.of("name", name, "count", tracks.size())));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("nowPlaying", s.getNowPlaying());
			out.put("queue", List.copyOf(s.getQueue()));
			out.put("history", List.copyOf(s.getHistory().subList(0, Math.min(s.getHistory().size(), HISTORY_PUBLIC))));
			out.put("speakerOnline", speakerOnline());
			out.put("cooldownSec", props.getCooldownSec());
			out.put("searchEnabled", youtube.hasApiKey());
			out.put("authRequired", !props.getAccessCode().isBlank());
			out.put("speakerKeyRequired", !props.getSpeakerKey().isBlank());
			out.put("version", props.getVersion());
			out.put("fallbackCount", currentFallbackPool().size());
			out.put("fallbackCategory", currentFallbackCategory());
			out.put("fallbackCategories", categories);
			out.put("playback", playback);
			return out;
		}
	}

	public void broadcast() {
		server.getBroadcastOperations().sendEvent("state", publicState());
	}

	public void sendState(SocketIOClient client) {
		client.sendEvent("state", publicState());
	}

	public long cooldownRemaining(String clientId) {
		Long last = store.state().getLastRequestAt().get(clientId);
		if (last == null) {
			return 0;
		}
		return Math.max(0, props.cooldownMs() - (System.currentTimeMillis() - last));
	}

	// ---------- 신청 ----------

	/**
	 * 대기열에 넣는다. 검사 순서는 스펙 §4 그대로:
	 * DUPLICATE → QUEUE_FULL → TOO_MANY_PENDING → COOLDOWN.
	 */
	public synchronized Item enqueue(Track track, String clientId) {
		JukeboxState s = store.state();
		boolean dup = (s.getNowPlaying() != null && s.getNowPlaying().getVideoId().equals(track.videoId()))
				|| s.getQueue().stream().anyMatch(q -> q.getVideoId().equals(track.videoId()));
		if (dup) {
			throw new JukeboxException("DUPLICATE");
		}
		if (s.getQueue().size() >= props.getMaxQueue()) {
			throw new JukeboxException("QUEUE_FULL");
		}
		long mine = s.getQueue().stream()
				.filter(q -> "request".equals(q.getSource()) && q.getRequestedBy() != null
						&& clientId.equals(q.getRequestedBy().clientId()))
				.count();
		if (mine >= props.getMaxPendingPerUser()) {
			throw new JukeboxException("TOO_MANY_PENDING");
		}
		long remaining = cooldownRemaining(clientId);
		if (remaining > 0) {
			throw new JukeboxException("COOLDOWN", remaining);
		}
		Item item = makeItem(track, clientId, "request", "");
		s.getQueue().add(item);
		s.getLastRequestAt().put(clientId, System.currentTimeMillis());
		if (s.getNowPlaying() == null) {
			advance("start"); // 아무것도 안 나오고 있으면 즉시 시작
		}
		else {
			store.save();
			broadcast();
		}
		return item;
	}

	/** 자기 신청곡 취소 → 쿨다운도 되돌려 준다. */
	public synchronized boolean remove(String itemId, String clientId) {
		JukeboxState s = store.state();
		int idx = -1;
		for (int i = 0; i < s.getQueue().size(); i++) {
			if (s.getQueue().get(i).getId().equals(itemId)) {
				idx = i;
				break;
			}
		}
		if (idx < 0) {
			return false;
		}
		Item item = s.getQueue().get(idx);
		if (item.getRequestedBy() == null || !item.getRequestedBy().clientId().equals(clientId)) {
			return false;
		}
		s.getQueue().remove(idx);
		s.getLastRequestAt().remove(clientId);
		store.save();
		broadcast();
		return true;
	}

	public Track cachedYouTubeSearch(String artist, String title) {
		String key = (artist + "|" + title).toLowerCase();
		Track cached = searchCache.get(key);
		if (cached != null) {
			return cached;
		}
		List<Track> results = youtube.search(artist + " " + title, 3);
		if (results.isEmpty()) {
			throw new JukeboxException("NOT_FOUND");
		}
		searchCache.put(key, results.get(0));
		return results.get(0);
	}

	// ---------- 재생 진행 ----------

	/** 다음 곡으로 넘김. reason: ended | skipped | error | start */
	public synchronized void advance(String reason) {
		JukeboxState s = store.state();
		Item current = s.getNowPlaying();
		if (current != null) {
			current.setEndedAt(System.currentTimeMillis());
			current.setEndReason(reason);
			s.getHistory().add(0, current);
			while (s.getHistory().size() > HISTORY_MAX) {
				s.getHistory().remove(s.getHistory().size() - 1);
			}
		}
		Item next = s.getQueue().isEmpty() ? pickFallback() : s.getQueue().remove(0);
		s.setNowPlaying(next);
		playback = new Playback(0, 0, next != null ? "loading" : "idle", System.currentTimeMillis());
		store.save();
		broadcast();
	}

	public synchronized void updatePlayback(SocketIOClient speaker, double position, double duration, String status) {
		playback = new Playback(position, duration, status == null || status.isBlank() ? "playing" : status,
				System.currentTimeMillis());
		server.getBroadcastOperations().sendEvent("tick", speaker, playback);
	}

	public synchronized boolean nowPlayingIs(String videoId) {
		Item np = store.state().getNowPlaying();
		return np != null && np.getVideoId().equals(videoId);
	}

	public synchronized void markFailed(String videoId) {
		failedVideoIds.add(videoId);
	}

	// ---------- 스피커 선출 ----------

	/** 이미 살아 있는 다른 스피커가 있으면 false. */
	public synchronized boolean claimSpeaker(SocketIOClient client) {
		UUID current = speakerSessionId;
		if (current != null && !current.equals(client.getSessionId()) && server.getClient(current) != null) {
			return false;
		}
		speakerSessionId = client.getSessionId();
		if (store.state().getNowPlaying() == null) {
			advance("start");
		}
		else {
			broadcast();
		}
		return true;
	}

	public synchronized void releaseSpeaker(SocketIOClient client) {
		if (!isSpeaker(client)) {
			return;
		}
		speakerSessionId = null;
		playback = playback.withStatus("idle");
		broadcast();
	}

	public synchronized boolean setFallbackCategory(String name) {
		if (name == null || !fallback.pools().containsKey(name)) {
			return false;
		}
		store.state().setFallbackCategory(name);
		store.save();
		broadcast();
		return true;
	}

	// ---------- 자동 재생 선곡 ----------

	public String currentFallbackCategory() {
		String selected = store.state().getFallbackCategory();
		Map<String, List<Track>> pools = fallback.pools();
		if (selected != null && pools.containsKey(selected)) {
			return selected;
		}
		return pools.isEmpty() ? "" : pools.keySet().iterator().next();
	}

	/** 선택한 카테고리가 비어 있으면 전체 카테고리를 합쳐서 쓴다. */
	public List<Track> currentFallbackPool() {
		Map<String, List<Track>> pools = fallback.pools();
		List<Track> pool = pools.getOrDefault(currentFallbackCategory(), List.of());
		if (!pool.isEmpty()) {
			return pool;
		}
		return pools.values().stream().flatMap(List::stream).toList();
	}

	private Item pickFallback() {
		JukeboxState s = store.state();
		List<Track> all = currentFallbackPool();
		// 풀이 클수록 최근 재생곡을 더 넓게 피한다 (최대 100곡)
		int avoid = Math.min(100, Math.max(15, all.size() / 2));
		Set<String> recent = new LinkedHashSet<>();
		s.getHistory().subList(0, Math.min(s.getHistory().size(), avoid)).forEach(h -> recent.add(h.getVideoId()));
		if (s.getNowPlaying() != null) {
			recent.add(s.getNowPlaying().getVideoId());
		}
		List<Track> usable = all.stream().filter(t -> !failedVideoIds.contains(t.videoId())).toList();
		List<Track> pool = usable.stream().filter(t -> !recent.contains(t.videoId())).toList();
		if (pool.isEmpty()) {
			pool = usable;
		}
		if (!pool.isEmpty()) {
			Track t = pool.get(random.nextInt(pool.size()));
			return makeItem(t, null, "fallback", currentFallbackCategory());
		}
		// 자동 목록도 없으면 과거에 정상 재생된 곡 중 랜덤 (침묵보다는 반복이 낫다)
		Set<String> seen = new LinkedHashSet<>();
		List<Item> played = s.getHistory().stream()
				.filter(h -> !"error".equals(h.getEndReason()) && !failedVideoIds.contains(h.getVideoId())
						&& seen.add(h.getVideoId()))
				.toList();
		List<Item> candidates = played.stream().filter(h -> !recent.contains(h.getVideoId())).toList();
		if (candidates.isEmpty()) {
			String nowId = s.getNowPlaying() == null ? null : s.getNowPlaying().getVideoId();
			candidates = played.stream().filter(h -> !h.getVideoId().equals(nowId)).toList();
		}
		if (candidates.isEmpty()) {
			candidates = played;
		}
		if (candidates.isEmpty()) {
			return null;
		}
		Item h = candidates.get(random.nextInt(candidates.size()));
		return makeItem(new Track(h.getVideoId(), h.getTitle(), h.getAuthor(), h.getThumb()), null, "fallback",
				currentFallbackCategory());
	}

	private Item makeItem(Track track, String clientId, String source, String category) {
		Item item = new Item();
		item.setId(UUID.randomUUID().toString());
		item.setVideoId(track.videoId());
		item.setTitle(track.title());
		item.setAuthor(track.author() == null ? "" : track.author());
		item.setThumb(track.thumb());
		item.setCategory(category);
		item.setRequestedBy(clientId == null ? null : new Item.Requester(clientId));
		item.setRequestedAt(System.currentTimeMillis());
		item.setSource(source);
		return item;
	}

	// ---------- 주기 작업 ----------

	/**
	 * 스피커는 켜져 있는데 틀 곡이 없어 멈춰 있으면 1분마다 다시 시도한다.
	 * 자동 목록이 전부 실패로 표시된 상태면 실패 기록을 지우고 처음부터 다시 돈다.
	 */
	@Scheduled(fixedRate = 60_000)
	public synchronized void retryIdleSpeaker() {
		if (!speakerOnline() || store.state().getNowPlaying() != null) {
			return;
		}
		List<Track> pool = currentFallbackPool();
		if (!pool.isEmpty() && pool.stream().allMatch(t -> failedVideoIds.contains(t.videoId()))) {
			failedVideoIds.clear();
		}
		advance("start");
	}

	/** 외부 모니터링용 (스펙 §4 타이머 30초). */
	@Scheduled(fixedRate = 30_000)
	public void heartbeat() {
		store.writeHeartbeat(speakerOnline());
	}

	/** 목록이 새로 로드되면 실패 기록을 비우고, 멈춰 있던 스피커를 다시 돌린다. */
	@EventListener
	public synchronized void onFallbackReloaded(FallbackReloadedEvent event) {
		failedVideoIds.clear();
		if (store.state().getNowPlaying() == null && speakerOnline()) {
			advance("start");
		}
		else {
			broadcast();
		}
	}
}
