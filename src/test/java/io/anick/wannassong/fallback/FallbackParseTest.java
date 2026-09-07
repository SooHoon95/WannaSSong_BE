package io.anick.wannassong.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = { "socketio.port=39312", "spring.data.redis.database=2" })
class FallbackParseTest {

	static {
		io.anick.wannassong.EmbeddedRedis.ensureRunning();
	}

	@Autowired
	FallbackService fallback;

	/** 클래스패스 기본 fallback.txt: [인기] chart:KR, [집중] search:… */
	@Test
	void parsesCategoriesAndSourceTypes() {
		Map<String, List<FallbackSource>> cats = fallback.parse();

		assertThat(cats).containsOnlyKeys("인기", "집중");
		assertThat(cats.get("인기")).containsExactly(new FallbackSource("chart", "KR"));
		assertThat(cats.get("집중")).singleElement()
				.satisfies(s -> assertThat(s.type()).isEqualTo("search"));
	}
}
