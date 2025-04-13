package br.com.downloader.downloader.controller;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ErrorController {

    @ExceptionHandler(Exception.class)
    public String handleException(Exception e, Model model) {
        e.printStackTrace();
        String message;
        if (e.getMessage().contains("yt-dlp")) {
            message = "O yt-dlp cagou tudo: " + e.getMessage();
        } else if (e.getMessage().contains("Arquivo")) {
            message = "O arquivo deu o fora: " + e.getMessage();
        } else {
            message = "Algo explodiu aqui: " + e.getMessage();
        }
        model.addAttribute("errorMessage", message);
        return "index";
    }
}