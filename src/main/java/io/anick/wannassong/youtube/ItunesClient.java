package io.anick.wannassong.youtube;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/** lib/itunes.js 이식: 자동완성용 무료·무제한 검색. */
@Slf4j
@Component
public class ItunesClient {

	private final RestClient http = RestClient.create();

	public List<Suggestion> search(String term, int limit) {
		try {
			JsonNode j = http.get()
					.uri("https://itunes.apple.com/search?media=music&entity=song&limit=" + limit + "&term="
							+ URLEncoder.encode(term, StandardCharsets.UTF_8))
					.retrieve()
					.body(JsonNode.class);
			List<Suggestion> out = new ArrayList<>();
			if (j != null) {
				for (JsonNode it : j.path("results")) {
					out.add(new Suggestion(it.path("artistName").asText(""), it.path("trackName").asText(""),
							it.path("artworkUrl100").asText(""), it.path("collectionName").asText("")));
				}
			}
			return out;
		}
		catch (Exception e) {
			log.warn("iTunes 검색 실패 \"{}\": {}", term, e.getMessage());
			return List.of();
		}
	}

	public record Suggestion(String artist, String title, String artwork, String album) {
	}
}
