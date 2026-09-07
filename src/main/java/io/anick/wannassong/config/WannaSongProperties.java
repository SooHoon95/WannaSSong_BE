package io.anick.wannassong.config;

import java.util.List;

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

	/** /api/info 가 알려 줄 외부 공개 포트. 비우면 server.port 를 쓴다. */
	private Integer publicPort;

	/** 브라우저가 붙어오는 Origin 목록. REST CORS 와 socket.io 양쪽에 쓴다. */
	private List<String> allowedOrigins = List.of("*");

	private String version = "0.0.1";

	public long cooldownMs() {
		return cooldownSec * 1000L;
	}
}
