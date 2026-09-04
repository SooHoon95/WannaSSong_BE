package io.anick.wannassong.jukebox;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/** 대기열·현재곡·히스토리가 같은 JSON 모양을 쓴다 (history 만 endedAt/endReason 추가). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Item {

	private String id;

	private String videoId;

	private String title;

	private String author;

	private String thumb;

	private String category;

	private Requester requestedBy;

	private long requestedAt;

	private String source;

	private Long endedAt;

	private String endReason;

	public record Requester(String clientId) {
	}
}
