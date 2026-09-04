package io.anick.wannassong.jukebox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** state.json 과 같은 구조. nowPlaying 은 재시작 시 항상 null. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JukeboxState {

	private Item nowPlaying;

	private List<Item> queue = new ArrayList<>();

	private List<Item> history = new ArrayList<>();

	private Map<String, Long> lastRequestAt = new HashMap<>();

	private String fallbackCategory = "";
}
