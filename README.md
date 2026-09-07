# WannaSSong_BE

WannaSSong 온프레미스 백엔드. REST + Socket.IO + Redis + 폴백 로드를 한 프로세스가 담당한다.

스피커 상태(`speakerSessionId`, `playback`, `failedVideoIds`)는 메모리에 있다 → **인스턴스 하나만 띄운다.**

## 프로파일

| 프로파일 | 파일 | Redis |
|---|---|---|
| `local` (기본) | `application-local.properties` | 단독 `localhost:6379` |
| `dev` | `application-dev.properties` | 개발서버 클러스터 `10.251.1.181:7000-7009` |
| `test` | `src/test/resources/application-test.properties` | `EmbeddedRedis` 자동 기동 (`localhost:6399`) |

공통 설정은 `application.properties`. 클러스터는 `SELECT` 를 지원하지 않아 DB 인덱스 분리가 안 되고, `wannasong:` 키 프리픽스로만 구분한다.

## 시크릿

`YT_API_KEY`, `ACCESS_CODE`, `SPEAKER_KEY` 는 커밋하지 않는다. 둘 중 하나로 넣는다.

1. `config/application.properties` — `/config/` 는 `.gitignore` 에 걸려 있고, Spring Boot 가
   `./config/` 를 기본 설정 위치로 읽는다 (클래스패스보다 우선).

   ```properties
   wannasong.yt-api-key=AIza...
   ```

2. 환경 변수 — `YT_API_KEY=AIza... ./gradlew bootRun`

`./config/` 가 클래스패스를 덮으므로 테스트가 실제 YouTube API 를 때리게 된다.
`build.gradle` 의 test 태스크가 `wannasong.yt-api-key` 를 빈 값으로 강제해 쿼터를 보호한다.

## 실행

로컬:

```bash
docker run -d --name wannasong-redis -p 6379:6379 redis:7-alpine
./gradlew bootRun
```

개발서버 (jar):

```bash
./gradlew bootJar     # build/libs/wannassong-0.0.1-SNAPSHOT.jar

SPRING_PROFILES_ACTIVE=dev \
CONTEXT_PATH=/jukebox \
SOCKETIO_CONTEXT=/jukebox/socket.io \
PUBLIC_PORT=443 \
YT_API_KEY=AIza... \
java -jar build/libs/wannassong-0.0.1-SNAPSHOT.jar
```

`CONTEXT_PATH` 를 주면 REST 가 `/jukebox/api/*` 로 내려가고, 루트 `/api/*` 는 404 가 된다.
socket.io 는 별 리스너라 `SOCKETIO_CONTEXT` 를 같이 맞춰야 클라이언트 경로가 일치한다.
`PUBLIC_PORT` 는 `/api/info` 의 `lanUrls`·`port` 에만 쓰인다 (프록시 앞단 포트).

> Windows Git Bash 에서 `CONTEXT_PATH=/jukebox java -jar ...` 로 띄우면 MSYS 가 값을
> Windows 경로로 바꿔 `ContextPath must start with '/'` 로 죽는다. PowerShell·cmd 를 쓰거나
> `--server.servlet.context-path=/jukebox` 를 프로그램 인자로 넘길 것. Linux 서버에선 문제없다.

Redis 에 못 붙으면 서비스는 뜨지만 상태가 하나도 남지 않는다. 기동 로그에
`Redis 에 붙지 못했다` ERROR 가 찍히니 확인할 것.

## 환경 변수

프로파일 기본값을 덮어쓸 때만 쓴다.

| 변수 | 기본 | 설명 |
|------|------|------|
| `SPRING_PROFILES_ACTIVE` | `local` | `local` \| `dev` |
| `PORT` | `3001` | REST 리슨 포트 |
| `CONTEXT_PATH` | — (루트) | 예: `/jukebox` → `/jukebox/api/*` |
| `SOCKETIO_PORT` | `3002` | Socket.IO 리슨 포트 |
| `SOCKETIO_CONTEXT` | `/socket.io` | `CONTEXT_PATH` 쓰면 같이 맞출 것 |
| `PUBLIC_PORT` | `PORT` 값 | `/api/info` 의 `lanUrls`·`port`. 프록시 앞단 포트 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / — | `local` 프로파일에서만 |
| `ALLOWED_ORIGINS` | `*` (local 은 `http://localhost:3000`) | 쉼표 구분. 프론트 공개 URL |
| `PUBLIC_URL` | — | QR·공유용 외부 URL |
| `ACCESS_CODE` | — | 설정 시 입장 코드 인증 |
| `SPEAKER_KEY` | — | 스피커 claim + `GET /api/feedback` 키 |
| `YT_API_KEY` | — | 없으면 `searchEnabled=false`, `chart:`/`search:`/`playlist:` 폴백 불가 |
| `COOLDOWN_SEC` | `300` | 신청 쿨다운(초) |
| `MAX_QUEUE` | `50` | 대기열 최대 |
| `MAX_PENDING_PER_USER` | `3` | clientId당 대기 중 신청 상한 |
| `FALLBACK_PLAYLIST` | — | 폴백에 추가할 재생목록 ID |
| `DATA_DIR` | `./data` | `fallback.txt` 위치. 없으면 클래스패스 기본값 |

전부 **기동 시 1회** 읽는다. 바꾸려면 재시작.

## 리버스 프록시

netty-socketio 는 Tomcat 포트를 공유할 수 없어 리스너가 둘이다. 같은 공개 도메인으로 묶는다.

```nginx
location /            { proxy_pass http://127.0.0.1:3000; }   # Next.js UI
location /api/        { proxy_pass http://127.0.0.1:3001; }   # CONTEXT_PATH 쓰면 location 도 맞출 것
location /socket.io/  {
    proxy_pass http://127.0.0.1:3002;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

`X-Forwarded-For` 를 넘겨야 REST 레이트 리밋이 진짜 클라이언트 IP 로 걸린다.

## Redis 키

| Key | 값 | 쓰기 |
|-----|-----|------|
| `wannasong:state` | `{ queue, history, lastRequestAt, fallbackCategory }` (nowPlaying 제외) | 300ms 디바운스 |
| `wannasong:feedback` | `FeedbackItem[]` max 1000 | `feedback` 이벤트 |
| `wannasong:fallback-pool` | `{ [카테고리]: Track[] }` 카테고리당 max 500 | 6시간 폴백 갱신 |
| `wannasong:heartbeat` | `{ speakerOnline, at }` | 30초 |

## 테스트

```bash
./gradlew test
```

`test` 프로파일 + 임베디드 Redis(포트 6399)를 자동으로 띄운다. 외부 Redis 불필요.
