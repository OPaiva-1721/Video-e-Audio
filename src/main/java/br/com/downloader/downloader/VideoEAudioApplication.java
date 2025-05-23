package br.com.downloader.downloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class VideoEAudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoEAudioApplication.class, args);
		System.out.println("Iniciando VideoEAudioApplication...");
		System.out.println("VideoEAudioApplication iniciado!");
	}
}
