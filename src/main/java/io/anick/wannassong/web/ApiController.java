package io.anick.wannassong.web;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.jukebox.JukeboxException;
import io.anick.wannassong.jukebox.JukeboxService;
import io.anick.wannassong.jukebox.StateStore;
import io.anick.wannassong.jukebox.Track;
import io.anick.wannassong.realtime.RateLimiter;
import io.anick.wannassong.youtube.ItunesClient;
import io.anick.wannassong.youtube.YouTubeClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 스펙 §2 REST. 검색·건의조회·헬스는 전부 여기, 소켓에는 없다. */
@Tag(name = "WannaSSong REST", description = "검색·건의조회·헬스. 대기열·재생은 Socket.IO 라 여기 없다.")
@Slf4j
@RestController
@RequiredArgsConstructor
public class ApiController {

	private static final int MAX_QUERY = 100;

	private final WannaSongProperties props;

	private final JukeboxService jukebox;

	private final StateStore store;

	private final ItunesClient itunes;

	private final YouTubeClient youtube;

	/** ponytail: IP 를 키로 한 프로세스 로컬 카운터. 단일 인스턴스라 충분하다. */
	private final RateLimiter ipLimits = new RateLimiter();

	@Value("${server.port:19060}")
	private int serverPort;

	/** 프록시 뒤면 브라우저가 보는 포트가 다르다. PUBLIC_PORT 가 있으면 그걸 알려 준다. */
	private int publicPort() {
		return props.getPublicPort() == null ? serverPort : props.getPublicPort();
	}

	// ---------- 검색 ----------

	/** iTunes 후보 (무료·무제한). 40/min/IP. */
	@Operation(summary = "iTunes 곡 후보 검색", description = "q 는 trim 후 최대 100자. 빈 문자열이면 results 는 빈 배열. KR 스토어가 0건이면 US 로 재시도. 40회/분/IP.")
	@PostMapping("/api/suggest")
	public Map<String, Object> suggest(@RequestBody(required = false) Query body, HttpServletRequest req) {
		limit(req, "suggest", 40, 60_000);
		String term = trim(body);
		return Map.of("ok", true, "results", term.isEmpty() ? List.of() : itunes.search(term, 8));
	}

	/** YouTube 직접 검색 (100유닛/회라 빡빡하게). 5/min/IP. */
	@Operation(summary = "YouTube 영상 검색", description = "videoCategoryId=10, videoEmbeddable=true, 최대 5건. YT_API_KEY 없으면 NO_API_KEY. 5회/분/IP.")
	@PostMapping("/api/ytsearch")
	public Map<String, Object> ytsearch(@RequestBody(required = false) Query body, HttpServletRequest req) {
		limit(req, "ytsearch", 5, 60_000);
		if (!youtube.hasApiKey()) {
			throw new JukeboxException("NO_API_KEY");
		}
		String term = trim(body);
		List<Track> results = term.isEmpty() ? List.of() : youtube.search(term, 5);
		return Map.of("ok", true, "results", results);
	}

	// ---------- 운영 ----------

	@Operation(summary = "헬스 체크", description = "speakerOnline 은 스피커 소켓이 살아 있는지.")
	@GetMapping("/api/health")
	public Map<String, Object> health() {
		return Map.of("ok", true, "speakerOnline", jukebox.speakerOnline());
	}

	@Operation(summary = "QR·공유용 서버 정보", description = "lanUrls 와 port 는 PUBLIC_PORT(없으면 server.port) 기준.")
	@GetMapping("/api/info")
	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("lanUrls", lanUrls());
		out.put("port", publicPort());
		out.put("version", props.getVersion());
		out.put("publicUrl", props.getPublicUrl());
		return out;
	}

	/** SPEAKER_KEY 가 설정돼 있으면 ?key= 가 맞아야 볼 수 있다. clientId 는 노출하지 않는다. */
	@Operation(summary = "건의사항 목록", description = "SPEAKER_KEY 가 설정돼 있으면 key 가 일치해야 한다. 응답에 clientId 는 없다.")
	@GetMapping("/api/feedback")
	public ResponseEntity<Object> feedback(@RequestParam(required = false) String key) {
		if (!props.getSpeakerKey().isBlank() && !props.getSpeakerKey().equals(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "key required"));
		}
		List<Map<String, Object>> list = store.feedback().stream().map(f -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", f.get("id"));
			item.put("text", f.get("text"));
			item.put("at", f.get("at"));
			return item;
		}).toList();
		return ResponseEntity.ok(list);
	}

	// ---------- 오류 → ERR_MSG ----------

	@ExceptionHandler(JukeboxException.class)
	public ResponseEntity<Map<String, Object>> onJukeboxError(JukeboxException e) {
		String code = e.getMessage();
		HttpStatus status = "RATE_LIMITED".equals(code) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status)
				.body(Map.of("ok", false, "error", JukeboxException.message(code, props.getMaxPendingPerUser())));
	}

	// ---------- 헬퍼 ----------

	private void limit(HttpServletRequest req, String endpoint, int max, long windowMs) {
		if (!ipLimits.allow(ip(req) + "|" + endpoint, max, windowMs)) {
			throw new JukeboxException("RATE_LIMITED");
		}
	}

	/** 리버스 프록시 뒤라 X-Forwarded-For 가 진짜 클라이언트다. */
	private static String ip(HttpServletRequest req) {
		String forwarded = req.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return String.valueOf(req.getRemoteAddr());
	}

	private static String trim(Query body) {
		String q = body == null || body.q() == null ? "" : body.q().trim();
		return q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
	}

	/** QR·공유용. NIC 의 non-internal IPv4. */
	private List<String> lanUrls() {
		List<String> out = new ArrayList<>();
		try {
			for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!nic.isUp() || nic.isLoopback()) {
					continue;
				}
				for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
					if (addr.getAddress().length == 4 && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
						out.add("http://" + addr.getHostAddress() + ":" + publicPort());
					}
				}
			}
		}
		catch (Exception e) {
			log.debug("LAN 주소 조회 실패: {}", e.getMessage());
		}
		return out;
	}

	public record Query(String q) {
	}

}
