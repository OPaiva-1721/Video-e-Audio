package br.com.downloader.downloader.Service;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import br.com.downloader.downloader.websocket.DownloadProgressMessage;
import br.com.downloader.downloader.websocket.ProgressNotificationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DownloadService {
    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of");
    private static final Pattern FILENAME_PATTERN = Pattern.compile("\\[download\\] Destination: (.+)");

    @Value("${yt-dlp.path}")
    private String ytDlpPath;

    @Value("${download.output.dir}")
    private String outputDir;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;
    
    @Autowired
    private ProgressNotificationService notificationService;
    
    @Autowired
    private DownloadRepository downloadRepository;

    @PostConstruct
    public void validatePaths() {
        if (!new File(ytDlpPath).exists()) {
            throw new IllegalStateException("yt-dlp não encontrado: " + ytDlpPath);
        }
        if (!new File(ffmpegPath).exists()) {
            throw new IllegalStateException("ffmpeg não encontrado: " + ffmpegPath);
        }
        
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
            throw new IllegalStateException("Não conseguiu criar diretório: " + outputDir);
        }
    }

    @Cacheable(value = "videoMetadata", key = "#url")
    public String getVideoTitle(String url) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--skip-download");
        command.add("--get-title");
        command.add(url);

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Timeout ao obter título do vídeo");
        }
        
        return output.toString().trim();
    }

    @Async
    public CompletableFuture<File> downloadFileAsync(Download download) throws IOException, InterruptedException {
        String url = download.getUrl();
        String format = download.getFormat();
        String quality = download.getQuality();
        
        // Gerar nome de arquivo baseado no título do vídeo
        String videoTitle;
        try {
            videoTitle = getVideoTitle(url);
            // Sanitizar o título para uso como nome de arquivo
            videoTitle = videoTitle.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
        } catch (Exception e) {
            logger.warn("Não foi possível obter o título do vídeo, usando timestamp: {}", e.getMessage());
            videoTitle = "video_" + System.currentTimeMillis();
        }
        
        String fileName = videoTitle + "." + format;
        String outputFile = outputDir + File.separator + fileName;
        
        // Atualizar o download com o nome do arquivo
        download.setFileName(fileName);
        download.setFilePath(outputFile);
        download.setStatus("Processando");
        download.setProgress(0);
        downloadRepository.save(download);
        
        // Notificar início do download
        notifyProgress(download);

        List<String> command = buildDownloadCommand(format, quality, outputFile, url);
        logger.info("Comando executado: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(outputDir));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        // Monitorar o progresso em thread separada
        Thread progressMonitor = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("yt-dlp output: {}", line);
                    
                    // Extrair progresso
                    Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                    if (progressMatcher.find()) {
                        try {
                            double progressValue = Double.parseDouble(progressMatcher.group(1));
                            int progressInt = (int) Math.round(progressValue);
                            
                            // Atualizar progresso no banco de dados
                            download.setProgress(progressInt);
                            downloadRepository.save(download);
                            
                            // Notificar via WebSocket
                            notifyProgress(download);
                        } catch (NumberFormatException e) {
                            logger.warn("Erro ao parsear progresso: {}", e.getMessage());
                        }
                    }
                    
                    // Extrair nome do arquivo de destino
                    Matcher filenameMatcher = FILENAME_PATTERN.matcher(line);
                    if (filenameMatcher.find()) {
                        String extractedFileName = filenameMatcher.group(1);
                        Path path = Paths.get(extractedFileName);
                        download.setFileName(path.getFileName().toString());
                        downloadRepository.save(download);
                    }
                }
            } catch (IOException e) {
                logger.error("Erro ao ler saída do processo: {}", e.getMessage());
            }
        });
        
        progressMonitor.start();

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            download.setStatus("Erro");
            download.setProgress(0);
            downloadRepository.save(download);
            notifyProgress(download);
            throw new RuntimeException("Download demorou muito e foi cancelado");
        }

        int exitCode = process.exitValue();
        logger.info("Exit code do yt-dlp: {}", exitCode);

        if (exitCode != 0) {
            download.setStatus("Erro");
            downloadRepository.save(download);
            notifyProgress(download);
            throw new RuntimeException("Erro no yt-dlp, exit code " + exitCode);
        }

        File file = new File(outputFile);
        if (!file.exists()) {
            download.setStatus("Erro");
            downloadRepository.save(download);
            notifyProgress(download);
            throw new RuntimeException("Arquivo não criado: " + outputFile);
        }

        // Atualizar status para concluído
        download.setStatus("Concluído");
        download.setProgress(100);
        downloadRepository.save(download);
        notifyProgress(download);
        
        logger.info("Download concluído: URL={}, Format={}, FilePath={}", download.getUrl(), download.getFormat(), download.getFilePath());
        return CompletableFuture.completedFuture(file);
    }
    
    private List<String> buildDownloadCommand(String format, String quality, String outputFile, String url) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--ffmpeg-location");
        command.add(ffmpegPath);
        command.add("--newline"); // Para melhor parsing do progresso
        
        switch (format) {
            case "mp3":
                command.add("--extract-audio");
                command.add("--audio-format");
                command.add("mp3");
                break;
            case "ogg":
                command.add("--extract-audio");
                command.add("--audio-format");
                command.add("vorbis");
                break;
            case "flac":
                command.add("--extract-audio");
                command.add("--audio-format");
                command.add("flac");
                break;
            case "webm":
                command.add("-f");
                if (quality != null && !quality.equals("best")) {
                    String qualityFilter = getQualityFilter(quality, "webm");
                    command.add(qualityFilter);
                } else {
                    command.add("bestvideo[ext=webm]+bestaudio[ext=webm]/best[ext=webm]/best");
                }
                break;
            case "mp4":
            default:
                command.add("-f");
                if (quality != null && !quality.equals("best")) {
                    String qualityFilter = getQualityFilter(quality, "mp4");
                    command.add(qualityFilter);
                } else {
                    command.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
                }
                break;
        }
        
        command.add("-o");
        command.add(outputFile);
        command.add(url);
        
        return command;
    }
    
    private String getQualityFilter(String quality, String format) {
        String heightFilter = switch (quality) {
            case "360p" -> "height<=360";
            case "480p" -> "height<=480";
            case "720p" -> "height<=720";
            case "1080p" -> "height<=1080";
            case "2160p" -> "height<=2160";
            default -> "height<=720"; // Default to 720p
        };
        
        if ("webm".equals(format)) {
            return "bestvideo[" + heightFilter + "][ext=webm]+bestaudio[ext=webm]/best[" + heightFilter + "][ext=webm]/best";
        } else {
            return "bestvideo[" + heightFilter + "][ext=mp4]+bestaudio[ext=m4a]/best[" + heightFilter + "][ext=mp4]/best";
        }
    }
    
    private void notifyProgress(Download download) {
        DownloadProgressMessage message = new DownloadProgressMessage(
            download.getId(),
            download.getUrl(),
            download.getFormat(),
            download.getQuality(),
            download.getStatus(),
            download.getProgress() != null ? download.getProgress() : 0,
            download.getFileName(),
            download.getFilePath()
        );
        
        notificationService.notifyProgressUpdate(message);
        
        if ("Concluído".equals(download.getStatus())) {
            notificationService.notifyDownloadCompleted(message);
        } else if ("Erro".equals(download.getStatus())) {
            notificationService.notifyError(message);
        }
    }
}