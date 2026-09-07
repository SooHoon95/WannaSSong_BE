package io.anick.wannassong.web;

import io.anick.wannassong.config.WannaSongProperties;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/** 프론트가 다른 origin 에서 /api/* 를 부를 때 (로컬 개발). 프록시 뒤 same-origin 이면 무해. */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

	private final WannaSongProperties props;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOriginPatterns(props.getAllowedOrigins().toArray(String[]::new))
				.allowedMethods("GET", "POST", "OPTIONS");
	}

}
