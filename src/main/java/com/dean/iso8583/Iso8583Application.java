package com.dean.iso8583;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;


import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class Iso8583Application {

	static void main(String[] args) {

		SpringApplication application = new SpringApplication(Iso8583Application.class);
		application.setBannerMode(Banner.Mode.LOG);

		ConfigurableApplicationContext context = application.run(args);
		Environment environment = context.getEnvironment();

		String port = environment.getProperty("local.server.port");
		String appName = environment.getProperty("spring.application.name", "ISO-8583");

		log.info("""
                        
                        -----------------------------------------------
                        🚀 {} is Running!
                        🌐 URL:     http://localhost:{}
                        📄 ISO TCP PORT: http://localhost:8583
                        -----------------------------------------------
                        """,
				appName, port
		);
	}

}
