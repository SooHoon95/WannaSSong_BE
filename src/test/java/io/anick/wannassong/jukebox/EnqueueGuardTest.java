package io.anick.wannassong.jukebox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.anick.wannassong.EmbeddedRedis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 스펙 §4 enqueue 검사 순서: DUPLICATE → QUEUE_FULL → TOO_MANY_PENDING → COOLDOWN. */
@SpringBootTest
@TestPropertySource(properties = {
		"socketio.port=39313",
		"spring.data.redis.database=3",
		"wannasong.cooldown-sec=300",
		"wannasong.max-queue=3",
		"wannasong.max-pending-per-user=2" })
class EnqueueGuardTest {

	static {
		EmbeddedRedis.ensureRunning();
	}

	@Autowired
	JukeboxService jukebox;

	@Autowired
	StateStore store;

	@BeforeEach
	void reset() {
		JukeboxState s = store.state();
		s.setNowPlaying(null);
		s.getQueue().clear();
		s.getHistory().clear();
		s.getLastRequestAt().clear();
	}

	private static Track track(String id) {
		return Track.of(id, "곡 " + id, "가수", null);
	}

	@Test
	void duplicateWinsOverEveryOtherGuard() {
		// 첫 곡은 nowPlaying 이 되고, 쿨다운도 걸린다
		jukebox.enqueue(track("aaaaaaaaaa1"), "u1");
		assertThat(store.state().getNowPlaying().getVideoId()).isEqualTo("aaaaaaaaaa1");

		// 같은 곡을 다시 → 쿨다운보다 DUPLICATE 가 먼저
		assertThatThrownBy(() -> jukebox.enqueue(track("aaaaaaaaaa1"), "u1"))
				.hasMessage("DUPLICATE");
	}

	@Test
	void cooldownIsLastAndCarriesRemainingMs() {
		jukebox.enqueue(track("bbbbbbbbbb1"), "u2");
		assertThatThrownBy(() -> jukebox.enqueue(track("bbbbbbbbbb2"), "u2"))
				.isInstanceOfSatisfying(JukeboxException.class, e -> {
					assertThat(e.getMessage()).isEqualTo("COOLDOWN");
					assertThat(e.getCooldownRemainingMs()).isPositive();
				});
	}

	@Test
	void queueFullBeatsPendingAndCooldown() {
		jukebox.enqueue(track("cccccccccc1"), "a"); // nowPlaying 으로 빠짐
		jukebox.enqueue(track("cccccccccc2"), "b");
		jukebox.enqueue(track("cccccccccc3"), "c");
		jukebox.enqueue(track("cccccccccc4"), "d"); // queue = 3 (max)

		assertThat(store.state().getQueue()).hasSize(3);
		assertThatThrownBy(() -> jukebox.enqueue(track("cccccccccc5"), "e"))
				.hasMessage("QUEUE_FULL");
	}

	@Test
	void tooManyPendingCountsOnlyOwnRequests() {
		jukebox.enqueue(track("dddddddddd1"), "solo"); // nowPlaying
		store.state().getLastRequestAt().clear();
		jukebox.enqueue(track("dddddddddd2"), "solo"); // queue 1
		store.state().getLastRequestAt().clear();
		jukebox.enqueue(track("dddddddddd3"), "solo"); // queue 2 = max-pending
		store.state().getLastRequestAt().clear();

		assertThatThrownBy(() -> jukebox.enqueue(track("dddddddddd4"), "solo"))
				.hasMessage("TOO_MANY_PENDING");
	}

}
