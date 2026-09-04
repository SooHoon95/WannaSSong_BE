package io.anick.wannassong.realtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 소켓별 슬라이딩 윈도우. 검색 스팸으로 YouTube 쿼터를 태우는 것을 막는다. */
public class RateLimiter {

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

	/** 허용되면 true. */
	public boolean allow(String key, int max, long windowMs) {
		Deque<Long> arr = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		long now = System.currentTimeMillis();
		synchronized (arr) {
			while (!arr.isEmpty() && now - arr.peekFirst() >= windowMs) {
				arr.pollFirst();
			}
			if (arr.size() >= max) {
				return false;
			}
			arr.addLast(now);
			return true;
		}
	}
}
