package io.anick.wannassong.fallback;

/** fallback.txt 한 줄. type: video | playlist | search | chart */
public record FallbackSource(String type, String value) {
}
