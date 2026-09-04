package io.anick.wannassong.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "wannasong")
public class WannaSongProperties {

	/** 상태·건의사항·누적 풀 저장 폴더. 클라우드에서는 볼륨 경로. */
	private String dataDir = "./data";

	private int cooldownSec = 300;

	private int maxQueue = 50;

	private int maxPendingPerUser = 3;

	/** 비우면 입장 코드 검사 없음. */
	private String accessCode = "";

	/** 비우면 아무 기기나 스피커가 될 수 있음. */
	private String speakerKey = "";

	private String ytApiKey = "";

	private String fallbackPlaylist = "";

	/** QR·공유용 외부 주소. */
	private String publicUrl = "";

	private String version = "0.0.1";

	public long cooldownMs() {
		return cooldownSec * 1000L;
	}
}
