package io.anick.wannassong.jukebox;

public record Playback(double position, double duration, String status, long updatedAt) {

	public static Playback idle() {
		return new Playback(0, 0, "idle", System.currentTimeMillis());
	}

	public Playback withStatus(String status) {
		return new Playback(position, duration, status, System.currentTimeMillis());
	}
}
