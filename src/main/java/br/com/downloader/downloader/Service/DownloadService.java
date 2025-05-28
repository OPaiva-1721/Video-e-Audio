package br.com.downloader.downloader.service;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import br.com.downloader.downloader.websocket.DownloadProgressMessage;
import br.com.downloader.downloader.websocket.ProgressNotificationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
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

    private final ProgressNotificationService notificationService;
    private final DownloadRepository downloadRepository;

    public DownloadService(ProgressNotificationService notificationService, DownloadRepository downloadRepository) {
        this.notificationService = notificationService;
        this.downloadRepository = downloadRepository;
    }

    @PostConstruct
    public void validatePaths() {
        if (!Files.exists(Paths.get(ytDlpPath))) {
            throw new IllegalStateException("yt-dlp não encontrado: " + ytDlpPath);
        }
        if (!Files.exists(Paths.get(ffmpegPath))) {
            throw new IllegalStateException("ffmpeg não encontrado: " + ffmpegPath);
        }

        Path outputDirPath = Paths.get(outputDir);
        if (!Files.exists(outputDirPath)) {
            try {
                Files.createDirectories(outputDirPath);
            } catch (IOException e) {
                throw new IllegalStateException("Não conseguiu criar diretório: " + outputDir, e);
            }
        }
    }

    @Cacheable(value = "videoMetadata", key = "#url")
    public String getVideoTitle(String url) throws IOException, InterruptedException {
        List<String> command = List.of(
                ytDlpPath,
                "--skip-download",
                "--get-title",
                url
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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

        String videoTitle;
        try {
            videoTitle = getVideoTitle(url);
            videoTitle = videoTitle.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
        } catch (Exception e) {
            logger.warn("Não foi possível obter o título do vídeo, usando timestamp: {}", e.getMessage());
            videoTitle = "video_" + System.currentTimeMillis();
        }

        String fileName = videoTitle + "." + format;
        Path outputFilePath = Paths.get(outputDir, fileName);

        download.setFileName(fileName);
        download.setFilePath(outputFilePath.toString());
        download.setStatus("Processando");
        download.setProgress(0);
        downloadRepository.save(download);

        notifyProgress(download);

        List<String> command = buildDownloadCommand(format, quality, outputFilePath.toString(), url);
        logger.info("Comando executado: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(outputDir));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        CompletableFuture<Void> progressMonitor = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("yt-dlp output: {}", line);

                    Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                    if (progressMatcher.find()) {
                        try {
                            double progressValue = Double.parseDouble(progressMatcher.group(1));
                            int progressInt = (int) Math.round(progressValue);

                            download.setProgress(progressInt);
                            downloadRepository.save(download);
                            notifyProgress(download);
                        } catch (NumberFormatException e) {
                            logger.warn("Erro ao parsear progresso: {}", e.getMessage());
                        }
                    }

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

        File file = outputFilePath.toFile();
        if (!file.exists()) {
            download.setStatus("Erro");
            downloadRepository.save(download);
            notifyProgress(download);
            throw new RuntimeException("Arquivo não criado: " + outputFilePath);
        }

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
        command.add("--newline");

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
            default -> "height<=720";
        };

        if ("webm".equals(format)) {
            return "bestvideo[" + heightFilter + "][ext=webm]+bestaudio[ext=webm]/best[" + heightFilter + "][ext=webm]/best";
        } else {
            return "bestvideo[" + heightFilter + "][ext=mp4]+bestaudio[ext=m4a]/best[" + heightFilter + "][ext=mp4]/best";
        }
    }


::contentReference[oaicite:0]{index=0}
