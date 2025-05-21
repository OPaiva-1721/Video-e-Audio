package br.com.downloader.downloader.controller;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@CrossOrigin(origins = "https://video-e-audio.netlify.app")
@RestController
@RequestMapping("/api")
public class DownloadController {
    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);

    private final DownloadRepository downloadRepository;

    @Value("${yt-dlp.path:/app/yt-dlp}")
    private String ytDlpPath;

    @Value("${ffmpeg.path:/app/ffmpeg-bin}")
    private String ffmpegPath;

    @Value("${download.output.dir:/app/downloads}")
    private String defaultOutputDir;

    public DownloadController(DownloadRepository downloadRepository) {
        this.downloadRepository = downloadRepository;
    }

    @PostMapping("/download")
public ResponseEntity<?> download(@RequestBody Download download) {
    try {
        logger.info("Iniciando download: {}", download);
        String url = download.getUrl();
        String format = download.getFormat();
        String quality = download.getQuality();
        String savePath = download.getSavePath();

        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("URL não pode ser vazia");
        }
        if (format == null || (!format.equals("mp3") && !format.equals("mp4"))) {
            return ResponseEntity.badRequest().body("Formato inválido: " + format);
        }
        if (savePath == null || savePath.trim().isEmpty()) {
            savePath = "output." + format;
        }

        File saveDir = new File(defaultOutputDir);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        String outputFile = new File(saveDir, savePath).getAbsolutePath();
        logger.debug("Caminho de saída: {}", outputFile);

        File ytDlpFile = new File(ytDlpPath);
        File ffmpegFile = new File(ffmpegPath);
        if (!ytDlpFile.exists()) {
            logger.error("yt-dlp não encontrado em: {}", ytDlpPath);
            return ResponseEntity.status(500).body("yt-dlp não encontrado em: " + ytDlpPath);
        }
        if (!ffmpegFile.exists()) {
            logger.error("ffmpeg não encontrado em: {}", ffmpegPath);
            return ResponseEntity.status(500).body("ffmpeg não encontrado em: " + ffmpegPath);
        }

        if (!saveDir.isDirectory()) {
            logger.error("Caminho não é um diretório válido: {}", saveDir.getAbsolutePath());
            return ResponseEntity.status(500).body("Caminho não é um diretório válido: " + saveDir.getAbsolutePath());
        }
        if (!saveDir.canWrite()) {
            logger.error("Sem permissão de escrita no diretório: {}", saveDir.getAbsolutePath());
            return ResponseEntity.status(500).body("Sem permissão de escrita no diretório: " + saveDir.getAbsolutePath());
        }

        long freeSpace = saveDir.getFreeSpace();
        long requiredSpace = 2_000_000_000L;
        if (freeSpace < requiredSpace) {
            logger.error("Espaço insuficiente: {} MB disponível, {} MB necessário", freeSpace / 1_000_000, requiredSpace / 1_000_000);
            return ResponseEntity.status(500).body("Espaço insuficiente no diretório: " + saveDir.getAbsolutePath());
        }

        File partFile = new File(saveDir, savePath + ".part");
        if (partFile.exists() && !partFile.delete()) {
            logger.warn("Não foi possível excluir arquivo .part: {}", partFile.getAbsolutePath());
        }

        // Testar acessibilidade do vídeo com um comando leve
        List<String> testCommand = new ArrayList<>();
        testCommand.add(ytDlpPath);
        testCommand.add("--skip-download");
        testCommand.add("--get-url");
        testCommand.add(url);
        ProcessBuilder testBuilder = new ProcessBuilder(testCommand);
        testBuilder.redirectErrorStream(true);
        Process testProcess = testBuilder.start();
        int testExitCode = testProcess.waitFor(1, TimeUnit.MINUTES);
        if (testExitCode != 0) {
            logger.error("Vídeo não acessível: {}", url);
            return ResponseEntity.status(400).body("Este vídeo não está disponível ou excedeu o limite de requisições.");
        }

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--ffmpeg-location");
        command.add(ffmpegPath);
        if (format.equals("mp3")) {
            command.add("--extract-audio");
            command.add("--audio-format");
            command.add("mp3");
        } else {
            String qualityFilter = switch (quality != null ? quality : "best") {
                case "360p" -> "bestvideo[height<=360]+bestaudio/best[height<=360]";
                case "720p" -> "bestvideo[height<=720]+bestaudio/best[height<=720]";
                case "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
                case "2160p" -> "bestvideo[height<=2160]+bestaudio/best[height<=2160]";
                default -> "best";
            };
            command.add("-f");
            command.add(qualityFilter.replace("best", "b")); // Usa "b" pra evitar o warning
        }
        command.add("-o");
        command.add(outputFile);
        command.add(url);

        logger.info("Comando: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(saveDir);
        builder.redirectErrorStream(true);
        Process process = null;

        try {
            process = builder.start();
            StringBuilder output = new StringBuilder();
            Process finalProcess = process;
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("yt-dlp output: {}", line);
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    logger.error("Erro ao ler saída: {}", e.getMessage());
                    output.append("Erro ao ler saída: ").append(e.getMessage()).append("\n");
                }
            });
            outputReader.start();

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            outputReader.join(1000);

            if (!finished) {
                process.destroyForcibly();
                logger.error("Download demorou muito e foi cancelado");
                return ResponseEntity.status(500).body("Download demorou muito e foi cancelado. Saída: " + output);
            }

            int exitCode = process.exitValue();
            logger.info("Código de saída: {}", exitCode);
            logger.debug("Saída do yt-dlp: {}", output);

            if (exitCode != 0) {
                logger.error("Erro ao baixar o arquivo. Código de saída: {}", exitCode);
                return ResponseEntity.status(500).body("Erro ao baixar o arquivo. Saída: " + output);
            }

            File file = new File(outputFile);
            if (!file.exists()) {
                logger.error("Arquivo não foi criado: {}", outputFile);
                return ResponseEntity.status(500).body("Arquivo não foi criado: " + outputFile);
            }

            download.setFilePath(outputFile);
            logger.info("Salvando download no banco: {}", download);
            downloadRepository.save(download);
            logger.info("Download salvo com ID: {}", download.getId());

            return ResponseEntity.ok().body("{\"message\": \"Download started\", \"id\": \"" + download.getId() + "\"}");
        } catch (Exception e) {
            logger.error("Erro durante o processamento do download: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Erro durante o processamento do download: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    } catch (Exception e) {
        logger.error("Erro ao processar download: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body("Erro ao processar o download: " + e.getMessage());
    }
}

    @GetMapping("/download-file")
    public ResponseEntity<Resource> downloadFile(@RequestParam("filePath") String filePath) throws IOException {
        logger.info("Solicitação para baixar arquivo: {}", filePath);
        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("Arquivo não encontrado: {}", filePath);
            return ResponseEntity.status(404).body(null);
        }

        Resource resource = new FileSystemResource(file);
        logger.info("Enviando arquivo: {}, tamanho: {} bytes", file.getName(), file.length());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }

    @GetMapping("/downloads")
    public ResponseEntity<List<Download>> listDownloads() {
        try {
            logger.info("Iniciando busca de downloads no banco MySQL...");
            List<Download> downloads = downloadRepository.findAll();
            logger.info("Downloads encontrados: {}", downloads.size());
            return ResponseEntity.ok(downloads);
        } catch (Exception e) {
            logger.error("Erro ao listar downloads: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        }
    }
}
