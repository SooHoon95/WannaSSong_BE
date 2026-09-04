package io.anick.wannassong.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "socketio")
public class SocketIoProperties {

	private String hostname = "0.0.0.0";

	private int port = 3000;

	/** socket.io-client 가 붙어오는 Origin. Vercel 도메인(NEXT_PUBLIC_REALTIME_URL 짝) 을 넣는다. */
	private String allowedOrigin = "*";

	private int pingIntervalMs = 25_000;

	private int pingTimeoutMs = 60_000;
}
