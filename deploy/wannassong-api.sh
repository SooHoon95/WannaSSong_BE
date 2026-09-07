#!/bin/bash
# WannaSSong API. 프로세스 하나가 REST(19060)와 Socket.IO(19061)를 같이 리슨한다.
# YT_API_KEY 등 시크릿은 /home/mobcomms/afin/wannassong-api/config/application.properties 에 둔다.

cd /home/mobcomms/afin/wannassong-api || exit 1

pid=$(ps -ef | grep wannassong-api.jar | grep server.port=19060 | grep -v grep | awk '{print $2}')
if [ -n "$pid" ]; then
    echo "Stopping WannaSSong API Server"
    # SIGTERM 으로 내려야 @PreDestroy 가 돌아 디바운스 대기 중인 대기열 상태를 Redis 에 쓴다.
    kill $pid
    sleep 5
    kill -9 $pid 2>/dev/null
fi

nohup /usr/java/jdk-17.0.2/bin/java -Dserver.port=19060 -Dsocketio.port=19061 -Xms256m -Xmx512m -jar /home/mobcomms/afin/wannassong-api/wannassong-api.jar --spring.profiles.active=dev >> output.log 2>&1 &
