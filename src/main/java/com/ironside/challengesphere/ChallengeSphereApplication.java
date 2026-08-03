package com.ironside.challengesphere;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@OpenAPIDefinition(
		info = @Info(
				title = "ChallengeSphere API",
				version = "1.0",
				description = "ChallengeSphere – The Ultimate way to challenge yourself!",
				contact = @Contact(
						name = "ChallengeSphere",
						email = "ironside-dev@challengesphere.com"
				),
				license = @License(
						name = "License",
						url = "https://challengesphere.com/license"
				)
		)
)
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
@EnableAsync
@EnableScheduling
@EnableCaching
public class ChallengeSphereApplication {
	public static void main(String[] args) {
		SpringApplication.run(ChallengeSphereApplication.class, args);
	}
}