package vn.nhom17.banhang;

import org.springframework.boot.SpringApplication;

public class TestBanhangBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(BanhangBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
