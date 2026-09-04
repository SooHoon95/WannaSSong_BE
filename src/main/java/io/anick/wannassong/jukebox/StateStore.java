package io.anick.wannassong.jukebox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.anick.wannassong.config.WannaSongProperties;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DATA_DIR 아래 JSON 파일 저장 (state / feedback / fallback-pool).
 * Vercel KV 로 옮길 때는 이 클래스의 read/write 만 갈아끼우면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateStore {

	private static final int FEEDBACK_MAX = 1000;

	private final WannaSongProperties props;

	private final ObjectMapper mapper;

	private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "state-saver");
		t.setDaemon(true);
		return t;
	});

	private ScheduledFuture<?> pending;

	private JukeboxState state;

	public Path dataDir() {
		return Path.of(props.getDataDir()).toAbsolutePath().normalize();
	}

	/** fallback.txt 는 DATA_DIR 에 있으면 그것을, 없으면 클래스패스 기본값을 쓴다. */
	public Path fallbackFile() {
		return dataDir().resolve("fallback.txt");
	}

	public synchronized JukeboxState state() {
		if (state == null) {
			state = read(dataDir().resolve("state.json"), JukeboxState.class, JukeboxState::new);
			state.setNowPlaying(null); // 재시작 시 현재곡은 복원하지 않는다
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
			write(dataDir().resolve("state.json"), snapshot);
		}
	}

	public List<Map<String, Object>> feedback() {
		return read(dataDir().resolve("feedback.json"), new TypeReference<List<Map<String, Object>>>() {
		}, ArrayList::new);
	}

	public synchronized void addFeedback(Map<String, Object> entry) {
		List<Map<String, Object>> list = feedback();
		list.add(0, entry);
		write(dataDir().resolve("feedback.json"), list.subList(0, Math.min(list.size(), FEEDBACK_MAX)));
	}

	public Map<String, List<Track>> pool() {
		return read(dataDir().resolve("fallback-pool.json"), new TypeReference<Map<String, List<Track>>>() {
		}, LinkedHashMap::new);
	}

	public void writePool(Map<String, List<Track>> pool) {
		write(dataDir().resolve("fallback-pool.json"), pool);
	}

	private <T> T read(Path file, Class<T> type, java.util.function.Supplier<T> fallback) {
		try {
			return mapper.readValue(Files.readString(file), type);
		}
		catch (Exception e) {
			return fallback.get();
		}
	}

	private <T> T read(Path file, TypeReference<T> type, java.util.function.Supplier<T> fallback) {
		try {
			return mapper.readValue(Files.readString(file), type);
		}
		catch (Exception e) {
			return fallback.get();
		}
	}

	private void write(Path file, Object value) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
		}
		catch (IOException | RuntimeException e) {
			log.warn("저장 실패 {}: {}", file, e.getMessage());
		}
	}

	@PreDestroy
	void flush() {
		saver.shutdown();
		writeStateNow();
	}
}
