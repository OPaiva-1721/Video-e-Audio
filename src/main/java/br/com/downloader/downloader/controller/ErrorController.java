package br.com.downloader.downloader.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ErrorController {
    private static final Logger logger = LoggerFactory.getLogger(ErrorController.class);

    @ExceptionHandler(Exception.class)
    public String handleException(Exception e, Model model) {
        logger.error("Erro capturado pelo ErrorController: ", e);

        String message;
        if (e.getMessage() != null) {
            if (e.getMessage().contains("yt-dlp")) {
                message = "Erro na ferramenta de download: " + e.getMessage();
                logger.error("Erro relacionado ao yt-dlp: {}", e.getMessage());
            } else if (e.getMessage().contains("Arquivo")) {
                message = "Erro ao manipular arquivo: " + e.getMessage();
                logger.error("Erro relacionado a arquivo: {}", e.getMessage());
            } else {
                message = "Ocorreu um erro inesperado: " + e.getMessage();
                logger.error("Erro inesperado: {}", e.getMessage());
            }
        } else {
            message = "Ocorreu um erro inesperado sem mensagem";
            logger.error("Erro sem mensagem detectado");
        }

        model.addAttribute("errorMessage", message);
        return "error";
    }
}