package com.rssolplan.edu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RssolPlanApplication {

	public static void main(String[] args) {
		SpringApplication.run(RssolPlanApplication.class, args);
		System.out.println(System.getenv("SPRING_DATASOURCE_URL"));

	}

}
