package io.anick.wannassong.realtime;

import com.corundumstudio.socketio.SocketIOServer;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocketIoLifecycle {

	private final SocketIOServer server;

	private final SocketIoProperties props;

	private final RealtimeGateway gateway;

	/** 자동 재생 목록 로드보다 먼저 리스너를 붙여 둔다. */
	@Order(0)
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		gateway.register();
		server.start();
		log.info("socket.io listening on {}:{}{}", props.getHostname(), props.getPort(), props.getContext());
	}
}
