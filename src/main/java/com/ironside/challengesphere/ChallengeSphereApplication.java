package com.ironside.challengesphere;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(
		info = @Info(
				title = "ChallengeSphere API",
				version = "1.0",
				description = "ChallengeSphere – The Ultimate way to challenge yourself!",
				contact = @Contact(
						name = "IronSide",
						email = "support@ironside.com"
				),
				license = @License(
						name = "License",
						url = "https://ironside.com/license"
				)
		)
)
public class ChallengeSphereApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChallengeSphereApplication.class, args);
	}

}
