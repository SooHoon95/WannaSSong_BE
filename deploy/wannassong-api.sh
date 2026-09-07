#!/bin/bash
# WannaSSong API. 프로세스 하나가 REST(19060)와 Socket.IO(19061)를 같이 띄운다.
# YT_API_KEY 같은 시크릿은 이 스크립트 옆 config/application.properties 에 둔다 (커밋 금지).

APP_DIR=$(cd "$(dirname "$0")" && pwd)
JAR=$APP_DIR/wannassong-api.jar
PORT=19060
JAVA=/usr/java/jdk-17.0.2/bin/java

cd "$APP_DIR" || exit 1   # ./config/ 와 output.log 위치를 고정한다

pid=$(ps -ef | grep "$JAR" | grep "server.port=$PORT" | grep -v grep | awk '{print $2}')
if [ -n "$pid" ]; then
    echo "Stopping WannaSSong API (pid $pid)"
    # SIGTERM 으로 내려야 @PreDestroy 가 돌아 디바운스 대기 중인 상태를 Redis 에 쓴다.
    # kill -9 로 죽이면 최대 300ms 분의 대기열 변경이 사라진다.
    kill "$pid"
    for _ in $(seq 1 15); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "Graceful stop timed out, forcing"
        kill -9 "$pid"
        sleep 2
    fi
fi

nohup "$JAVA" \
    -Dserver.port=$PORT \
    -Dsocketio.port=19061 \
    -Xms256m -Xmx512m \
    -jar "$JAR" \
    --spring.profiles.active=dev \
    >> output.log 2>&1 &

echo "Started WannaSSong API pid $!  (REST :$PORT, socket.io :19061)"
