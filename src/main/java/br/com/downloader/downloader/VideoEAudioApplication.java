package br.com.downloader.downloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableCaching
public class VideoEAudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoEAudioApplication.class, args);
		System.out.println("Iniciando VideoEAudioApplication...");
		SpringApplication.run(VideoEAudioApplication.class, args);
		System.out.println("VideoEAudioApplication iniciado!");
	}
}
