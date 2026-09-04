package io.anick.wannassong.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.jukebox.JukeboxService;
import io.anick.wannassong.jukebox.StateStore;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** server.js 의 REST 엔드포인트 이식. Next.js 로 옮겨간 것도 있지만 Java 단독 운영을 위해 남긴다. */
@RestController
@RequiredArgsConstructor
public class ApiController {

	private final WannaSongProperties props;

	private final JukeboxService jukebox;

	private final StateStore store;

	@GetMapping("/api/health")
	public Map<String, Object> health() {
		return Map.of("ok", true, "speakerOnline", jukebox.speakerOnline());
	}

	@GetMapping("/api/info")
	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("version", props.getVersion());
		out.put("publicUrl", props.getPublicUrl());
		return out;
	}

	/** SPEAKER_KEY 가 설정돼 있으면 ?key= 가 맞아야 볼 수 있다. */
	@GetMapping("/api/feedback")
	public ResponseEntity<?> feedback(@RequestParam(required = false) String key) {
		if (!props.getSpeakerKey().isBlank() && !props.getSpeakerKey().equals(key)) {
			return ResponseEntity.status(401).body(Map.of("error", "key required"));
		}
		List<Map<String, Object>> list = store.feedback().stream()
				.map(f -> Map.of("id", f.get("id"), "text", f.get("text"), "at", f.get("at")))
				.map(m -> (Map<String, Object>) new LinkedHashMap<String, Object>(m))
				.toList();
		return ResponseEntity.ok(list);
	}
}
