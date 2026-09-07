package io.anick.wannassong.realtime;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.DataListener;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.jukebox.Item;
import io.anick.wannassong.jukebox.JukeboxException;
import io.anick.wannassong.jukebox.JukeboxService;
import io.anick.wannassong.jukebox.StateStore;
import io.anick.wannassong.jukebox.Track;
import io.anick.wannassong.youtube.YouTubeClient;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * socket.io-client v4 이벤트 처리 (server.js io.on('connection') 이식).
 * 클라→서버: identify, request, remove, speaker:claim/tick/ended/error/skip/release,
 * fallback:set, feedback. 서버→클라: state, tick, me.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeGateway {

	private static final String KEY_CLIENT_ID = "clientId";

	private static final String KEY_AUTHED = "authed";

	private static final String KEY_LIMITER = "limiter";

	private final SocketIOServer server;

	private final JukeboxService jukebox;

	private final StateStore store;

	private final WannaSongProperties props;

	private final YouTubeClient youtube;

	/** 스피커 전용 이벤트는 페이로드가 없을 수 있어 Object 로 받는다. */
	public void register() {
		server.addConnectListener(this::onConnect);
		server.addDisconnectListener(this::onDisconnect);
		on("identify", this::onIdentify);
		on("request", this::onRequest);
		on("remove", this::onRemove);
		on("speaker:claim", this::onSpeakerClaim);
		on("speaker:tick", this::onSpeakerTick);
		on("speaker:ended", this::onSpeakerEnded);
		on("speaker:error", this::onSpeakerError);
		on("speaker:skip", (client, data, ack) -> {
			if (jukebox.isSpeaker(client)) {
				jukebox.advance("skipped");
			}
		});
		on("speaker:release", (client, data, ack) -> jukebox.releaseSpeaker(client));
		on("fallback:set", this::onFallbackSet);
		on("feedback", this::onFeedback);
	}

	@SuppressWarnings("unchecked")
	private void on(String event, DataListener<Map<String, Object>> listener) {
		server.addEventListener(event, Map.class, (DataListener<Map>) (client, data, ack) -> {
			try {
				listener.onData(client, data == null ? Map.of() : (Map<String, Object>) data, ack);
			}
			catch (JukeboxException e) {
				Map<String, Object> err = new LinkedHashMap<>();
				err.put("ok", false);
				err.put("error", errMsg(e.getMessage()));
				if (e.getCooldownRemainingMs() > 0) {
					err.put("cooldownRemainingMs", e.getCooldownRemainingMs());
				}
				sendAck(ack, err);
			}
			catch (Exception e) {
				log.warn("[{}] 처리 실패: {}", event, e.toString());
				sendAck(ack, Map.of("ok", false, "error", errMsg("UNKNOWN")));
			}
		});
	}

	private void onConnect(SocketIOClient client) {
		client.set(KEY_AUTHED, props.getAccessCode().isBlank()); // 입장 코드가 없으면 누구나
		client.set(KEY_LIMITER, new RateLimiter());
		// ponytail: connect 리스너 안에서 바로 emit 하면 socket.io v4 핸드셰이크 ack 와 겹쳐
		// 클라이언트가 패킷을 버린다. 100ms 뒤에 보내고, identify 때 한 번 더 보내 보정한다.
		CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
			if (client.isChannelOpen()) {
				jukebox.sendState(client);
			}
		});
	}

	private void onDisconnect(SocketIOClient client) {
		jukebox.releaseSpeaker(client);
	}

	// ---------- 신원 ----------

	private void onIdentify(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		if (!limit(client, "identify", 20, 60_000)) {
			return;
		}
		String clientId = str(p.get("clientId"), 64);
		if (clientId.isEmpty()) {
			return;
		}
		boolean authed = props.getAccessCode().isBlank() || props.getAccessCode().equals(str(p.get("code"), 64));
		client.set(KEY_CLIENT_ID, clientId);
		client.set(KEY_AUTHED, authed);
		client.sendEvent("me", Map.of("ok", authed, "authRequired", !props.getAccessCode().isBlank(),
				"cooldownRemainingMs", jukebox.cooldownRemaining(clientId)));
		jukebox.sendState(client); // connect 직후 state 를 놓쳤을 경우의 보정
	}

	/** 신청. kind: itunes {artist,title} | video {videoId,title,author,thumb}. url 탭은 스코프 밖. */
	private void onRequest(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		requireAuth(client);
		requireLimit(client, "request", 10, 60_000);
		String clientId = clientId(client);
		if (clientId.isEmpty()) {
			throw new JukeboxException("AUTH_REQUIRED");
		}
		Track track = resolveRequest(p);
		Item item = jukebox.enqueue(track, clientId);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("ok", true);
		res.put("item", item);
		res.put("cooldownRemainingMs", jukebox.cooldownRemaining(clientId));
		sendAck(ack, res);
	}

	private Track resolveRequest(Map<String, Object> p) {
		String kind = str(p.get("kind"), 20);
		switch (kind) {
			case "video" -> {
				// 프론트 ytsearch 결과의 videoId 재사용 — YouTube search 를 다시 부르지 않는다.
				String videoId = youtube.parseVideoId(str(p.get("videoId"), 100));
				if (videoId == null) {
					throw new JukeboxException("BAD_URL");
				}
				String title = str(p.get("title"), 200);
				if (title.isBlank()) {
					Track meta = youtube.oembed(videoId); // 메타가 비었을 때만 보강
					if (meta != null) {
						return meta;
					}
				}
				return Track.of(videoId, title, str(p.get("author"), 100), str(p.get("thumb"), 500));
			}
			case "itunes" -> {
				return jukebox.cachedYouTubeSearch(str(p.get("artist"), 200), str(p.get("title"), 200));
			}
			default -> throw new JukeboxException("BAD_URL");
		}
	}

	/** 자기 신청곡 취소 → 쿨다운도 되돌려 준다. */
	private void onRemove(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		if (!authed(client)) {
			sendAck(ack, Map.of("ok", false));
			return;
		}
		boolean removed = jukebox.remove(str(p.get("id"), 64), clientId(client));
		sendAck(ack, removed ? Map.of("ok", true, "cooldownRemainingMs", 0) : Map.of("ok", false));
	}

	// ---------- 스피커(실제 재생 기기) 전용 ----------

	private void onSpeakerClaim(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		if (!limit(client, "claim", 10, 60_000)) {
			sendAck(ack, Map.of("ok", false, "reason", errMsg("RATE_LIMITED")));
			return;
		}
		if (!props.getSpeakerKey().isBlank() && !props.getSpeakerKey().equals(str(p.get("key"), 128))) {
			log.warn("[speaker] 잘못된 키로 claim 시도 ip={}", client.getRemoteAddress());
			sendAck(ack, Map.of("ok", false, "reason", "스피커 키가 틀렸습니다.", "needKey", true));
			return;
		}
		if (!jukebox.claimSpeaker(client)) {
			sendAck(ack, Map.of("ok", false, "reason", "이미 다른 기기가 재생 중입니다."));
			return;
		}
		sendAck(ack, Map.of("ok", true));
	}

	private void onSpeakerTick(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		if (!jukebox.isSpeaker(client)) {
			return;
		}
		jukebox.updatePlayback(client, num(p.get("position")), num(p.get("duration")), str(p.get("status"), 20));
	}

	private void onSpeakerEnded(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		String videoId = str(p.get("videoId"), 20);
		if (jukebox.isSpeaker(client) && jukebox.nowPlayingIs(videoId)) {
			jukebox.advance("ended");
		}
	}

	/** code 'stalled' = 클라이언트 워치독이 멈춤을 감지한 것. */
	private void onSpeakerError(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		String videoId = str(p.get("videoId"), 20);
		if (!jukebox.isSpeaker(client) || !jukebox.nowPlayingIs(videoId)) {
			return;
		}
		log.warn("[player] 재생 실패 code={} video={} 다음 곡으로", str(p.get("code"), 40), videoId);
		jukebox.markFailed(videoId);
		// 연속 실패 시 서버·YouTube 에 부담 주지 않도록 1.5초 텀
		java.util.concurrent.CompletableFuture
				.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
				.execute(() -> jukebox.advance("error"));
	}

	/** 자동 재생 카테고리 변경 (스피커 기기만). 지금 곡은 그대로, 다음 자동 재생부터 적용. */
	private void onFallbackSet(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		boolean ok = jukebox.isSpeaker(client) && jukebox.setFallbackCategory(str(p.get("name"), 20));
		sendAck(ack, Map.of("ok", ok));
	}

	// ---------- 문의 / 건의사항 ----------

	private void onFeedback(SocketIOClient client, Map<String, Object> p, AckRequest ack) {
		requireAuth(client);
		requireLimit(client, "feedback", 3, 10 * 60_000);
		String body = str(p.get("text"), 500).trim();
		if (body.length() < 2) {
			throw new JukeboxException("FEEDBACK_EMPTY");
		}
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("id", UUID.randomUUID().toString());
		entry.put("text", body);
		entry.put("at", Instant.now().toString());
		entry.put("clientId", clientId(client).isEmpty() ? null : clientId(client));
		store.addFeedback(entry);
		log.info("[feedback] {}", body.replaceAll("\\s+", " "));
		sendAck(ack, Map.of("ok", true));
	}

	// ---------- 헬퍼 ----------

	private String errMsg(String code) {
		return JukeboxException.message(code, props.getMaxPendingPerUser());
	}

	private void sendAck(AckRequest ack, Object payload) {
		if (ack != null && ack.isAckRequested()) {
			ack.sendAckData(payload);
		}
	}

	private RateLimiter limiter(SocketIOClient client) {
		RateLimiter limiter = client.get(KEY_LIMITER);
		if (limiter == null) {
			limiter = new RateLimiter();
			client.set(KEY_LIMITER, limiter);
		}
		return limiter;
	}

	private boolean limit(SocketIOClient client, String key, int max, long windowMs) {
		return limiter(client).allow(key, max, windowMs);
	}

	private void requireLimit(SocketIOClient client, String key, int max, long windowMs) {
		if (!limit(client, key, max, windowMs)) {
			throw new JukeboxException("RATE_LIMITED");
		}
	}

	private boolean authed(SocketIOClient client) {
		return Boolean.TRUE.equals(client.get(KEY_AUTHED));
	}

	private void requireAuth(SocketIOClient client) {
		if (!authed(client)) {
			throw new JukeboxException("AUTH_REQUIRED");
		}
	}

	private String clientId(SocketIOClient client) {
		String id = client.get(KEY_CLIENT_ID);
		return id == null ? "" : id;
	}

	private static String str(Object value, int max) {
		if (value == null) {
			return "";
		}
		String s = String.valueOf(value);
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static double num(Object value) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		}
		catch (RuntimeException e) {
			return 0;
		}
	}
}
