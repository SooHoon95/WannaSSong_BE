package io.anick.wannassong.jukebox;

import java.util.Map;

/** ERR_MSG 코드를 던지는 예외. 메시지 표는 Node ERR_MSG 와 1:1. */
public class JukeboxException extends RuntimeException {

	public JukeboxException(String code) {
		super(code);
	}

	public static String message(String code, int maxPendingPerUser) {
		Map<String, String> msg = Map.ofEntries(
				Map.entry("COOLDOWN", "아직 쿨다운 중입니다."),
				Map.entry("DUPLICATE", "이미 대기열에 있는 곡입니다."),
				Map.entry("NOT_FOUND", "YouTube에서 해당 곡을 찾지 못했습니다."),
				Map.entry("BAD_URL", "YouTube 링크를 인식할 수 없습니다."),
				Map.entry("UNAVAILABLE", "재생할 수 없는 영상입니다(비공개/삭제)."),
				Map.entry("NO_API_KEY", "YouTube API 키가 설정되지 않아 검색은 불가합니다. 링크를 붙여넣어 주세요."),
				Map.entry("QUOTA_EXCEEDED", "오늘 YouTube 검색 한도를 모두 썼습니다. 링크 붙여넣기는 계속 됩니다."),
				Map.entry("AUTH_REQUIRED", "입장 코드가 틀렸거나 입력되지 않았습니다."),
				Map.entry("RATE_LIMITED", "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
				Map.entry("FEEDBACK_EMPTY", "내용을 입력해 주세요."),
				Map.entry("QUEUE_FULL", "대기열이 가득 찼습니다. 잠시 후 다시 신청해 주세요."),
				Map.entry("TOO_MANY_PENDING",
						"대기 중인 내 신청곡이 이미 " + maxPendingPerUser + "곡입니다. 재생된 뒤 다시 신청해 주세요."));
		String m = msg.get(code);
		return m != null ? m : "처리 중 오류가 났습니다. 다시 시도해 주세요.";
	}
}
