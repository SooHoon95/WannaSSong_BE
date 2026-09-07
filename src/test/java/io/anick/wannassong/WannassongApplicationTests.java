package io.anick.wannassong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = { "socketio.port=39310", "spring.data.redis.database=0" })
class WannassongApplicationTests {

	static {
		EmbeddedRedis.ensureRunning();
	}

	@Test
	void contextLoads() {
	}

}
