package io.anick.wannassong.youtube;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** lib/itunes.ts 이식: 자동완성용 무료·무제한 검색. KR 스토어가 비면 US 로 재시도. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItunesClient {

	private final ObjectMapper mapper;

	private final RestClient http = RestClient.create();

	/** artist|title 기준 중복 제거(대소문자 무시). */
	public List<Suggestion> search(String term, int limit) {
		List<Suggestion> out = query(term, limit, "KR");
		return out.isEmpty() ? query(term, limit, "US") : out;
	}

	private List<Suggestion> query(String term, int limit, String country) {
		try {
			// iTunes 는 Content-Type 을 text/javascript 로 준다 → 문자열로 받아서 직접 파싱한다.
			String raw = http.get()
					.uri("https://itunes.apple.com/search?media=music&entity=song&limit=" + limit + "&country="
							+ country + "&term=" + URLEncoder.encode(term, StandardCharsets.UTF_8))
					.retrieve()
					.body(String.class);
			JsonNode j = raw == null || raw.isBlank() ? null : mapper.readTree(raw);
			List<Suggestion> out = new ArrayList<>();
			Set<String> seen = new LinkedHashSet<>();
			if (j != null) {
				for (JsonNode it : j.path("results")) {
					String artist = it.path("artistName").asText("");
					String title = it.path("trackName").asText("");
					if (title.isBlank() || !seen.add((artist + "|" + title).toLowerCase())) {
						continue;
					}
					out.add(new Suggestion(artist, title, it.path("collectionName").asText(""),
							it.path("artworkUrl100").asText(""), it.path("trackTimeMillis").asLong(0)));
				}
			}
			return out;
		}
		catch (Exception e) {
			log.warn("iTunes 검색 실패 \"{}\" ({}): {}", term, country, e.getMessage());
			return List.of();
		}
	}

	/** 스펙 §2 POST /api/suggest 응답 항목. */
	public record Suggestion(String artist, String title, String album, String artwork, long durationMs) {
	}

}
