package io.anick.wannassong.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "socketio")
public class SocketIoProperties {

	private String hostname = "0.0.0.0";

	private int port = 3002;

	/**
	 * socket.io-client 가 붙는 경로. context-path 아래에 배포하면 앞에 붙여야 한다
	 * (예: CONTEXT_PATH=/jukebox → /jukebox/socket.io).
	 */
	private String context = "/socket.io";

	private int pingIntervalMs = 25_000;

	private int pingTimeoutMs = 60_000;

}
