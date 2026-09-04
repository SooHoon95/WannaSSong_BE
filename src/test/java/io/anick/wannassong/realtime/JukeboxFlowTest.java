package io.anick.wannassong.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 실제 socket.io-client 로 접속해 identify → request → speaker claim → ended 흐름을 확인한다. */
@SpringBootTest
@TestPropertySource(properties = {
		"socketio.port=39311",
		"wannasong.access-code=secret",
		"wannasong.speaker-key=spk",
		"wannasong.cooldown-sec=0",
		"wannasong.data-dir=build/test-data" })
class JukeboxFlowTest {

	private static final String VIDEO_ID = "dQw4w9WgXcQ";

	private Socket socket;

	private final LinkedBlockingQueue<JSONObject> states = new LinkedBlockingQueue<>();

	private final LinkedBlockingQueue<JSONObject> meEvents = new LinkedBlockingQueue<>();

	@BeforeEach
	void connect() throws Exception {
		socket = IO.socket("http://localhost:39311");
		CompletableFuture<Void> connected = new CompletableFuture<>();
		socket.on(Socket.EVENT_CONNECT, args -> connected.complete(null));
		socket.on("state", args -> states.add(firstObject(args)));
		socket.on("me", args -> meEvents.add(firstObject(args)));
		socket.connect();
		connected.get(5, TimeUnit.SECONDS);
	}

	@AfterEach
	void disconnect() {
		socket.disconnect();
		socket.close();
	}

	@Test
	void requestPlaysAndSpeakerAdvancesToFallback() throws Exception {
		assertThat(awaitState(s -> true)).isNotNull(); // 접속 직후 state 1회

		// 입장 코드가 틀리면 거부
		socket.emit("identify", new JSONObject().put("clientId", "t1").put("code", "wrong"));
		assertThat(meEvents.poll(5, TimeUnit.SECONDS).optBoolean("ok")).isFalse();

		socket.emit("identify", new JSONObject().put("clientId", "t1").put("code", "secret"));
		assertThat(meEvents.poll(5, TimeUnit.SECONDS).optBoolean("ok")).isTrue();

		// 코드 없이 신청하면 AUTH_REQUIRED 였어야 하므로, 인증 후에는 통과
		JSONObject requested = ack("request",
				new JSONObject().put("kind", "video").put("videoId", VIDEO_ID).put("title", "테스트곡"));
		assertThat(requested.optBoolean("ok")).isTrue();

		// 스피커가 없어도 첫 곡은 바로 현재곡이 된다 (Node 와 동일)
		JSONObject playing = awaitState(s -> !s.isNull("nowPlaying")
				&& VIDEO_ID.equals(s.optJSONObject("nowPlaying").optString("videoId")));
		assertThat(playing.optJSONObject("nowPlaying").optString("source")).isEqualTo("request");
		assertThat(playing.optJSONArray("queue").length()).isZero();

		// 스피커 키가 틀리면 거부
		JSONObject badKey = ack("speaker:claim", new JSONObject().put("key", "nope"));
		assertThat(badKey.optBoolean("ok")).isFalse();
		assertThat(badKey.optBoolean("needKey")).isTrue();

		assertThat(ack("speaker:claim", new JSONObject().put("key", "spk")).optBoolean("ok")).isTrue();
		assertThat(awaitState(s -> s.optBoolean("speakerOnline"))).isNotNull();

		// 곡이 끝나면 다음 곡 선정: 대기열이 비었으니 자동 재생(여기서는 과거 재생곡)으로 이어진다
		socket.emit("speaker:ended", new JSONObject().put("videoId", VIDEO_ID));
		JSONObject next = awaitState(s -> !s.isNull("nowPlaying")
				&& "fallback".equals(s.optJSONObject("nowPlaying").optString("source")));
		assertThat(next.optJSONArray("history").length()).isPositive();
	}

	@Test
	void rejectsRequestWithoutAccessCode() throws Exception {
		JSONObject res = ack("request", new JSONObject().put("kind", "video").put("videoId", VIDEO_ID));
		assertThat(res.optBoolean("ok")).isFalse();
		assertThat(res.optString("error")).contains("입장 코드");
	}

	/** 인자가 여러 개 실려 와도 첫 JSON 객체만 집는다. */
	private static JSONObject firstObject(Object[] args) {
		for (Object a : args) {
			if (a instanceof JSONObject o) {
				return o;
			}
		}
		throw new AssertionError("JSON 객체 인자가 없다: " + java.util.Arrays.deepToString(args));
	}

	private JSONObject ack(String event, JSONObject payload) throws Exception {
		CompletableFuture<JSONObject> result = new CompletableFuture<>();
		socket.emit(event, new Object[] { payload }, args -> result.complete(firstObject(args)));
		return result.get(5, TimeUnit.SECONDS);
	}

	private JSONObject awaitState(Predicate<JSONObject> match) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			JSONObject s = states.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (s == null) {
				break;
			}
			if (match.test(s)) {
				return s;
			}
		}
		throw new AssertionError("기대한 state 를 받지 못했다");
	}
}
