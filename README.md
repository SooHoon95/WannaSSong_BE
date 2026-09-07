# WannaSSong_BE

WannaSSong 온프레미스 백엔드. REST + Socket.IO + Redis + 폴백 로드를 한 프로세스가 담당한다.

스피커 상태(`speakerSessionId`, `playback`, `failedVideoIds`)는 메모리에 있다 → **인스턴스 하나만 띄운다.**

## 프로파일

| 프로파일 | 파일 | Redis |
|---|---|---|
| `dev` (기본) | `application-dev.properties` | 개발서버 클러스터 `10.251.1.181:7000-7009` |
| `local` | `application-local.properties` | 단독 `localhost:6379` (Docker) |
| `test` | `src/test/resources/application-test.properties` | `EmbeddedRedis` 자동 기동 (`localhost:6399`) |

공통 설정은 `application.properties`. 클러스터는 `SELECT` 를 지원하지 않아 DB 인덱스 분리가 안 되고, `wannasong:` 키 프리픽스로만 구분한다.

프로파일을 지정하지 않으면 `dev` 로 뜬다 (`spring.profiles.default=dev`). IntelliJ 에서
그냥 Run 해도 개발서버 Redis 에 붙는다. 오프라인으로 작업할 때만 `local` 을 켠다.

> **`.properties` 는 ASCII 만 쓴다.** Spring 은 `.properties` 를 ISO-8859-1 로 읽는다
> (`java.util.Properties` 스펙). 한글을 넣으면 `# ê°ë° ìë²` 처럼 깨진다.
> 한글이 필요하면 `fallback.txt`(UTF-8) 에 두거나 `\uXXXX` 로 이스케이프할 것.

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

로컬 (오프라인, `local` 프로파일):

```bash
docker run -d --name wannasong-redis -p 6379:6379 redis:7-alpine
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

개발서버 (jar):

```bash
./gradlew bootJar     # build/libs/wannassong-0.0.1-SNAPSHOT.jar

SPRING_PROFILES_ACTIVE=dev \
PUBLIC_PORT=443 \
YT_API_KEY=AIza... \
java -jar build/libs/wannassong-0.0.1-SNAPSHOT.jar
```

## 경로

context path 는 `/wannassong` 이 기본값이다. 루트 `/api/*` 는 404.

| | 경로 |
|---|---|
| REST | `http://<host>:3001/wannassong/api/*` |
| Socket.IO | `http://<host>:3002/wannassong/socket.io/` |
| Swagger | `http://<host>:3001/wannassong/swagger-ui.html` |

`CONTEXT_PATH` 로 바꿀 수 있다. 빈 값(`CONTEXT_PATH=`)이면 루트에서 서비스한다.
바꿀 때는 `SOCKETIO_CONTEXT` 도 같이 맞춰야 클라이언트 경로가 일치한다 —
socket.io 는 별 리스너라 context path 를 자동으로 물려받지 않는다.
`PUBLIC_PORT` 는 `/api/info` 의 `lanUrls`·`port` 에만 쓰인다 (프록시 앞단 포트).

> Windows Git Bash 에서 `CONTEXT_PATH=/foo java -jar ...` 로 띄우면 MSYS 가 값을
> Windows 경로로 바꿔 `ContextPath must start with '/'` 로 죽는다. PowerShell·cmd 를 쓰거나
> `--server.servlet.context-path=/foo` 를 프로그램 인자로 넘길 것. Linux 서버에선 문제없다.

Redis 에 못 붙으면 서비스는 뜨지만 상태가 하나도 남지 않는다. 기동 로그에
`Redis 에 붙지 못했다` ERROR 가 찍히니 확인할 것.

## 환경 변수

프로파일 기본값을 덮어쓸 때만 쓴다.

| 변수 | 기본 | 설명 |
|------|------|------|
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` \| `local` |
| `PORT` | `3001` | REST 리슨 포트 |
| `CONTEXT_PATH` | `/wannassong` | 빈 값이면 루트. `SOCKETIO_CONTEXT` 도 같이 맞출 것 |
| `SOCKETIO_PORT` | `3002` | Socket.IO 리슨 포트 |
| `SOCKETIO_CONTEXT` | `/wannassong/socket.io` | socket.io 는 context path 를 자동 상속하지 않는다 |
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

## Swagger

기동 후 `http://localhost:3001/wannassong/swagger-ui.html`
(OpenAPI JSON 은 `/wannassong/v3/api-docs`).

REST 5개만 나온다. 대기열·재생·스피커는 Socket.IO 이벤트라 OpenAPI 로 표현되지 않는다 —
`identify`, `request`, `remove`, `speaker:claim/tick/ended/error/skip/release`, `fallback:set`,
`feedback` → `state`, `tick`, `me`. 계약은 이 문서와 `backend-onprem-handoff.md` 참고.

아래 nginx 설정은 `/wannassong/api/` 와 `/wannassong/socket.io/` 만 프록시하므로 Swagger 는
외부에 노출되지 않는다. 서버 포트로 직접 접속해야 한다. 외부에 열려면
`location /wannassong/swagger-ui/` 와 `location /wannassong/v3/api-docs` 를 추가할 것.

## 리버스 프록시

netty-socketio 는 Tomcat 포트를 공유할 수 없어 리스너가 둘이다. 같은 공개 도메인으로 묶는다.

`http` 블록에 (`server` 안이 아니다):

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

`server` 블록에:

```nginx
# Next.js UI
location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

# REST
location /wannassong/api/ {
    proxy_pass http://127.0.0.1:3001;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

# Socket.IO — WebSocket upgrade 필수
location /wannassong/socket.io/ {
    proxy_pass http://127.0.0.1:3002;
    proxy_http_version 1.1;
    proxy_set_header Upgrade    $http_upgrade;
    proxy_set_header Connection $connection_upgrade;

    proxy_set_header Host              $host;
    proxy_set_header Origin            $http_origin;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # 연결이 오래 열려 있다. 기본 60s 면 유휴 소켓이 끊긴다 (pingTimeout 60s, pingInterval 25s).
    proxy_read_timeout  3600s;
    proxy_send_timeout  3600s;
    proxy_buffering     off;   # long-polling 응답이 버퍼에 갇히지 않게
}
```

세 가지가 각각 없으면 이렇게 깨진다:

| 빠뜨린 것 | 증상 |
|---|---|
| `Upgrade` / `Connection` 헤더 | websocket 업그레이드 실패. polling 으로만 붙어 `tick` 이 느려짐 |
| `proxy_read_timeout` 상향 | 60초마다 소켓 끊김 → 재연결 반복, 스피커가 계속 release 됨 |
| `X-Forwarded-For` | REST 레이트 리밋이 nginx IP 하나로 뭉쳐서 전원이 429 |

`proxy_pass` 뒤에 슬래시를 붙이지 말 것. 붙이면 URI 가 잘려 `/wannassong/socket.io` 경로가
안 맞는다 (`SOCKETIO_CONTEXT` 와 일치해야 한다).

동작 확인:

```bash
curl -s "https://<도메인>/wannassong/socket.io/?EIO=4&transport=polling"
# 0{"sid":"...","upgrades":["websocket"],"pingInterval":25000,"pingTimeout":60000}

curl -i -s -o /dev/null -w '%{http_code}\n' \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "https://<도메인>/wannassong/socket.io/?EIO=4&transport=websocket"
# 101 이어야 한다. 200/400 이면 upgrade 설정이 안 먹은 것
```

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
