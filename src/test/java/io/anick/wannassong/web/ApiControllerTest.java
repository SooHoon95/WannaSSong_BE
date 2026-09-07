package io.anick.wannassong.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.anick.wannassong.EmbeddedRedis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 스펙 §2 REST. 네트워크를 타지 않는 경로만 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"socketio.port=39314",
		"spring.data.redis.database=4",
		"wannasong.speaker-key=spk",
		"wannasong.yt-api-key=" })
class ApiControllerTest {

	static {
		EmbeddedRedis.ensureRunning();
	}

	@Autowired
	MockMvc mvc;

	@Test
	void healthAndInfo() throws Exception {
		mvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.speakerOnline").value(false));

		mvc.perform(get("/api/info"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lanUrls").isArray())
				.andExpect(jsonPath("$.port").isNumber())
				.andExpect(jsonPath("$.version").exists());
	}

	@Test
	void feedbackNeedsSpeakerKey() throws Exception {
		mvc.perform(get("/api/feedback"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("key required"));

		mvc.perform(get("/api/feedback").param("key", "spk"))
				.andExpect(status().isOk());
	}

	@Test
	void suggestWithEmptyQueryReturnsEmpty() throws Exception {
		mvc.perform(post("/api/suggest").contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.results").isEmpty());
	}

	/** 키가 없으면 NO_API_KEY, 5회를 넘기면 429 RATE_LIMITED. 한 메서드에서 예산을 다 쓴다. */
	@Test
	void ytsearchWithoutKeyThenRateLimited() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/ytsearch").contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"iu\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error").value("YouTube API 키가 설정되지 않아 검색은 불가합니다. 링크를 붙여넣어 주세요."));
		}
		mvc.perform(post("/api/ytsearch").contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"iu\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value("요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."));
	}

}
