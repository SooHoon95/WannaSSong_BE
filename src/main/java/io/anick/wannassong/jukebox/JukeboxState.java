package io.anick.wannassong.jukebox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** wannasong:state 의 구조 (nowPlaying 제외). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JukeboxState {

	/** Redis 에 넣지 않는다 — 재시작 시 재생 중이던 곡은 복원하지 않는다 (스펙 §5). */
	@JsonIgnore
	private Item nowPlaying;

	private List<Item> queue = new ArrayList<>();

	private List<Item> history = new ArrayList<>();

	private Map<String, Long> lastRequestAt = new HashMap<>();

	private String fallbackCategory = "";

}
