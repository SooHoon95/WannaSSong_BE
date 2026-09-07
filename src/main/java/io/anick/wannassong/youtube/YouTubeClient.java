package io.anick.wannassong.youtube;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.anick.wannassong.config.WannaSongProperties;
import io.anick.wannassong.jukebox.JukeboxException;
import io.anick.wannassong.jukebox.Track;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/** lib/youtube.js 이식: oembed(키 불필요) + Data API v3 (검색·재생목록·인기차트). */
@Slf4j
@Component
public class YouTubeClient {

	private static final Pattern ID_ONLY = Pattern.compile("^[A-Za-z0-9_-]{11}$");

	private static final Pattern IN_URL = Pattern.compile(
			"(?:youtu\\.be/|/shorts/|/embed/|/live/|[?&]v=)([A-Za-z0-9_-]{11})");

	private static final Pattern ENTITY = Pattern.compile("&(#[xX]?[0-9A-Fa-f]+|[a-zA-Z]+);");

	private final WannaSongProperties props;

	private final RestClient http = RestClient.create();

	public YouTubeClient(WannaSongProperties props) {
		this.props = props;
	}

	public boolean hasApiKey() {
		return !props.getYtApiKey().isBlank();
	}

	/** youtu.be/ID, watch?v=ID, /shorts/ID, /embed/ID, 그리고 11자 ID 그대로. */
	public String parseVideoId(String input) {
		if (input == null) {
			return null;
		}
		String s = input.trim();
		if (ID_ONLY.matcher(s).matches()) {
			return s;
		}
		Matcher m = IN_URL.matcher(s);
		return m.find() ? m.group(1) : null;
	}

	/** 키 없이 제목·채널·썸네일. 비공개/삭제면 null. */
	public Track oembed(String videoId) {
		try {
			JsonNode j = http.get()
					.uri("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + videoId)
					.retrieve()
					.body(JsonNode.class);
			if (j == null) {
				return null;
			}
			return Track.of(videoId, j.path("title").asText(videoId), j.path("author_name").asText(""),
					j.path("thumbnail_url").asText(""));
		}
		catch (Exception e) {
			log.debug("oembed 실패 {}: {}", videoId, e.getMessage());
			return null;
		}
	}

	/** search.list 1회 = 100유닛. videoCategoryId=10 은 Music. */
	public List<Track> search(String query, int max) {
		requireKey();
		JsonNode j = get("search?part=snippet&type=video&videoCategoryId=10&videoEmbeddable=true&maxResults="
				+ Math.min(max, 50) + "&q=" + enc(query));
		List<Track> out = new ArrayList<>();
		for (JsonNode it : j.path("items")) {
			String id = it.path("id").path("videoId").asText("");
			if (!id.isBlank()) {
				out.add(track(id, it.path("snippet")));
			}
		}
		return out;
	}

	/** 재생목록 최대 200곡 (50곡/페이지, 1유닛/페이지). */
	public List<Track> playlistItems(String playlistId) {
		requireKey();
		List<Track> out = new ArrayList<>();
		String pageToken = "";
		for (int page = 0; page < 4; page++) {
			JsonNode j = get("playlistItems?part=snippet&maxResults=50&playlistId=" + enc(playlistId)
					+ (pageToken.isBlank() ? "" : "&pageToken=" + pageToken));
			for (JsonNode it : j.path("items")) {
				JsonNode snippet = it.path("snippet");
				String id = snippet.path("resourceId").path("videoId").asText("");
				if (!id.isBlank()) {
					out.add(track(id, snippet));
				}
			}
			pageToken = j.path("nextPageToken").asText("");
			if (pageToken.isBlank()) {
				break;
			}
		}
		return out;
	}

	/** 그 나라 인기 음악 50곡 = 1유닛. */
	public List<Track> popularMusic(String region) {
		requireKey();
		JsonNode j = get("videos?part=snippet&chart=mostPopular&videoCategoryId=10&maxResults=50&regionCode="
				+ enc(region));
		List<Track> out = new ArrayList<>();
		for (JsonNode it : j.path("items")) {
			String id = it.path("id").asText("");
			if (!id.isBlank()) {
				out.add(track(id, it.path("snippet")));
			}
		}
		return out;
	}

	private Track track(String videoId, JsonNode snippet) {
		JsonNode thumbs = snippet.path("thumbnails");
		String thumb = thumbs.path("high").path("url").asText(thumbs.path("default").path("url").asText(""));
		return Track.of(videoId, decode(snippet.path("title").asText(videoId)),
				decode(snippet.path("channelTitle").asText("")), thumb);
	}

	/**
	 * Data API 는 제목을 HTML 이스케이프해서 준다 ("Rock &amp; Roll").
	 * ponytail: YouTube 가 쓰는 5개 + 숫자 참조만 푼다. 전체 엔티티 표가 필요하면 commons-text 로 교체.
	 */
	static String decode(String s) {
		if (s == null || s.indexOf('&') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		Matcher m = ENTITY.matcher(s);
		int last = 0;
		while (m.find()) {
			out.append(s, last, m.start());
			String name = m.group(1);
			switch (name) {
				case "amp" -> out.append('&');
				case "lt" -> out.append('<');
				case "gt" -> out.append('>');
				case "quot" -> out.append('"');
				case "apos" -> out.append('\'');
				case "nbsp" -> out.append(' ');
				default -> {
					if (name.startsWith("#")) {
						String digits = name.substring(1);
						int radix = digits.startsWith("x") || digits.startsWith("X") ? 16 : 10;
						out.appendCodePoint(Integer.parseInt(radix == 16 ? digits.substring(1) : digits, radix));
					}
					else {
						out.append(m.group());
					}
				}
			}
			last = m.end();
		}
		return out.append(s, last, s.length()).toString();
	}

	private JsonNode get(String pathAndQuery) {
		try {
			JsonNode j = http.get()
					.uri("https://www.googleapis.com/youtube/v3/" + pathAndQuery + "&key=" + props.getYtApiKey())
					.retrieve()
					.body(JsonNode.class);
			return j == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : j;
		}
		catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == 403 && e.getResponseBodyAsString().contains("quota")) {
				throw new JukeboxException("QUOTA_EXCEEDED");
			}
			throw e;
		}
	}

	private void requireKey() {
		if (!hasApiKey()) {
			throw new JukeboxException("NO_API_KEY");
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
