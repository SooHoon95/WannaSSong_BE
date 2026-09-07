package io.anick.wannassong.youtube;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Data API 가 돌려주는 제목의 HTML 이스케이프 해제. */
class HtmlEntityTest {

	@Test
	void decodesNamedAndNumericEntities() {
		assertThat(YouTubeClient.decode("Rock &amp; Roll")).isEqualTo("Rock & Roll");
		assertThat(YouTubeClient.decode("&quot;Lilac&quot; &#39;live&#39;")).isEqualTo("\"Lilac\" 'live'");
		assertThat(YouTubeClient.decode("&#54620;&#44544;")).isEqualTo("한글");
		assertThat(YouTubeClient.decode("&#x41;&lt;b&gt;")).isEqualTo("A<b>");
	}

	@Test
	void leavesPlainAndUnknownTextAlone() {
		assertThat(YouTubeClient.decode("IU - Lilac")).isEqualTo("IU - Lilac");
		assertThat(YouTubeClient.decode("100&nope; 200")).isEqualTo("100&nope; 200");
		assertThat(YouTubeClient.decode(null)).isNull();
	}

}
