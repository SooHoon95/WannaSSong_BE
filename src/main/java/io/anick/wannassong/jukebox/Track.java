package io.anick.wannassong.jukebox;

public record Track(String videoId, String title, String author, String thumb) {

	public static Track of(String videoId, String title, String author, String thumb) {
		return new Track(videoId, title == null ? videoId : title, author == null ? "" : author,
				thumb == null || thumb.isBlank() ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg" : thumb);
	}
}
