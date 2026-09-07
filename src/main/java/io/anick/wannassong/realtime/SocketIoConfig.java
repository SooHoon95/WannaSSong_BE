package io.anick.wannassong.realtime;

import java.util.List;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;

import io.anick.wannassong.config.WannaSongProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(SocketIoProperties.class)
public class SocketIoConfig {

	@Bean(destroyMethod = "stop")
	public SocketIOServer socketIOServer(SocketIoProperties socketio, WannaSongProperties props) {
		Configuration config = new Configuration();
		config.setHostname(socketio.getHostname());
		config.setPort(socketio.getPort());
		config.setContext(socketio.getContext());
		config.setOrigin(origin(props.getAllowedOrigins()));
		config.setPingInterval(socketio.getPingIntervalMs());
		config.setPingTimeout(socketio.getPingTimeoutMs());
		return new SocketIOServer(config);
	}

	/**
	 * ponytail: netty-socketio 2.0.14 의 setOrigin 은 문자열 하나만 받는다.
	 * origin 이 여러 개면 검사를 못 하니 열어 두고 경고한다. 좁히려면 리버스 프록시에서 막을 것.
	 */
	private String origin(List<String> allowed) {
		if (allowed.size() == 1 && !"*".equals(allowed.get(0))) {
			return allowed.get(0);
		}
		if (allowed.size() > 1) {
			log.warn("socket.io Origin 검사 생략 — allowed-origins 가 {}개. 프록시에서 제한할 것: {}", allowed.size(), allowed);
		}
		return null;
	}

}
