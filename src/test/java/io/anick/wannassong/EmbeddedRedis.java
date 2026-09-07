package io.anick.wannassong;

import java.io.IOException;

import redis.embedded.RedisServer;

/**
 * 테스트용 로컬 Redis. 프로덕션은 외부 Redis 를 쓴다.
 * ponytail: Spring 테스트 컨텍스트가 여러 개라 JVM 당 한 번만 띄운다. 종료는 JVM 종료에 맡긴다.
 */
public final class EmbeddedRedis {

	public static final int PORT = 6399;

	static {
		try {
			RedisServer server = RedisServer.newRedisServer().port(PORT).build();
			server.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.stop();
				}
				catch (IOException ignored) {
					// JVM 종료 중이라 할 게 없다
				}
			}));
		}
		catch (IOException e) {
			throw new IllegalStateException("임베디드 Redis 기동 실패", e);
		}
	}

	private EmbeddedRedis() {
	}

	/** 테스트 컨텍스트가 뜰 때 static 초기화를 강제한다. */
	public static void ensureRunning() {
	}

}
