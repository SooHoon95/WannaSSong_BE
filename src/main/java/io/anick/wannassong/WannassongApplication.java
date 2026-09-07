package io.anick.wannassong;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@OpenAPIDefinition(info = @Info(title = "WannaSSong Backend", version = "0.0.1",
		description = "REST 는 검색·건의조회·헬스뿐이다. 대기열·재생·스피커는 Socket.IO 이벤트라 이 문서에 없다 "
				+ "(identify, request, remove, speaker:claim/tick/ended/error/skip/release, fallback:set, feedback "
				+ "→ state, tick, me). 자세한 계약은 README 와 backend-onprem-handoff.md 참고."))
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class WannassongApplication {

	public static void main(String[] args) {
		SpringApplication.run(WannassongApplication.class, args);
	}

}
