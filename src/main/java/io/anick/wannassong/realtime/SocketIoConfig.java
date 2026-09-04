package io.anick.wannassong.realtime;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(SocketIoProperties.class)
public class SocketIoConfig {

	@Bean(destroyMethod = "stop")
	public SocketIOServer socketIOServer(SocketIoProperties props) {
		Configuration config = new Configuration();
		config.setHostname(props.getHostname());
		config.setPort(props.getPort());
		config.setOrigin("*".equals(props.getAllowedOrigin()) ? null : props.getAllowedOrigin());
		config.setPingInterval(props.getPingIntervalMs());
		config.setPingTimeout(props.getPingTimeoutMs());
		return new SocketIOServer(config);
	}
}
