package io.anick.wannassong.jukebox;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.anick.wannassong.config.WannaSongProperties;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 영속화. 값은 JSON 문자열 한 덩어리로 넣는다.
 * fallback.txt 만 파일로 남는다 (사람이 편집하는 입력이라 DATA_DIR 아래).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateStore {

	public static final String K_STATE = "wannasong:state";

	public static final String K_FEEDBACK = "wannasong:feedback";

	public static final String K_POOL = "wannasong:fallback-pool";

	public static final String K_HEARTBEAT = "wannasong:heartbeat";

	private static final int FEEDBACK_MAX = 1000;

	private final WannaSongProperties props;

	private final ObjectMapper mapper;

	private final StringRedisTemplate redis;

	private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "state-saver");
		t.setDaemon(true);
		return t;
	});

	private ScheduledFuture<?> pending;

	private JukeboxState state;

	/**
	 * Redis 가 없어도 서비스는 돌지만 상태가 하나도 남지 않는다.
	 * 조용히 데이터를 잃는 게 최악이라 기동 때 한 번 크게 알린다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void checkRedis() {
		try {
			redis.execute((RedisCallback<String>) conn -> conn.ping());
			log.info("Redis 연결 확인");
		}
		catch (Exception e) {
			log.error("Redis 에 붙지 못했다 — 대기열·히스토리·건의사항이 재시작 시 모두 사라진다. 원인: {}", e.getMessage());
		}
	}

	public Path dataDir() {
		return Path.of(props.getDataDir()).toAbsolutePath().normalize();
	}

	/** fallback.txt 는 DATA_DIR 에 있으면 그것을, 없으면 클래스패스 기본값을 쓴다. */
	public Path fallbackFile() {
		return dataDir().resolve("fallback.txt");
	}

	/** nowPlaying 은 @JsonIgnore 라 Redis 에 없다 = 재시작 시 항상 null. */
	public synchronized JukeboxState state() {
		if (state == null) {
			state = read(K_STATE, JukeboxState.class, JukeboxState::new);
		}
		return state;
	}

	/** 300ms 디바운스 저장 (Node 의 save() 와 동일). */
	public synchronized void save() {
		if (pending != null) {
			pending.cancel(false);
		}
		pending = saver.schedule(this::writeStateNow, 300, TimeUnit.MILLISECONDS);
	}

	private void writeStateNow() {
		JukeboxState snapshot;
		synchronized (this) {
			snapshot = state;
		}
		if (snapshot != null) {
			write(K_STATE, snapshot);
		}
	}

	public List<Map<String, Object>> feedback() {
		return read(K_FEEDBACK, new TypeReference<List<Map<String, Object>>>() {
		}, ArrayList::new);
	}

	public synchronized void addFeedback(Map<String, Object> entry) {
		List<Map<String, Object>> list = feedback();
		list.add(0, entry);
		write(K_FEEDBACK, list.subList(0, Math.min(list.size(), FEEDBACK_MAX)));
	}

	public Map<String, List<Track>> pool() {
		return read(K_POOL, new TypeReference<Map<String, List<Track>>>() {
		}, LinkedHashMap::new);
	}

	public void writePool(Map<String, List<Track>> pool) {
		write(K_POOL, pool);
	}

	public void writeHeartbeat(boolean speakerOnline) {
		write(K_HEARTBEAT, Map.of("speakerOnline", speakerOnline, "at", Instant.now().toString()));
	}

	private <T> T read(String key, Class<T> type, Supplier<T> fallback) {
		return parse(key, raw -> mapper.readValue(raw, type), fallback);
	}

	private <T> T read(String key, TypeReference<T> type, Supplier<T> fallback) {
		return parse(key, raw -> mapper.readValue(raw, type), fallback);
	}

	private <T> T parse(String key, Parser<T> parser, Supplier<T> fallback) {
		try {
			String raw = redis.opsForValue().get(key);
			return raw == null || raw.isBlank() ? fallback.get() : parser.parse(raw);
		}
		catch (Exception e) {
			log.warn("읽기 실패 {}: {}", key, e.getMessage());
			return fallback.get();
		}
	}

	private void write(String key, Object value) {
		try {
			redis.opsForValue().set(key, mapper.writeValueAsString(value));
		}
		catch (Exception e) {
			log.warn("저장 실패 {}: {}", key, e.getMessage());
		}
	}

	@PreDestroy
	void flush() {
		saver.shutdown();
		writeStateNow();
	}

	private interface Parser<T> {

		T parse(String raw) throws Exception;

	}

}
