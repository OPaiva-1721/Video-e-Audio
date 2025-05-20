package br.com.downloader.downloader.controller;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@Controller
public class DownloadController {
    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);

    @Autowired
    private DownloadRepository downloadRepository;

    @Value("${yt-dlp.path:/app/yt-dlp}")
    private String ytDlpPath;

    @Value("${ffmpeg.path:/app/ffmpeg}")
    private String ffmpegPath;

    @Value("${download.output.dir:/app/downloads}")
    private String defaultOutputDir;

    @GetMapping("/")
    public String index(Model model) {
        logger.info("Acessando página inicial");
        model.addAttribute("download", new Download());
        return "index";
    }

    @PostMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> download(@RequestBody Download download) {
        try {
            logger.info("Iniciando download: {}", download);
            String url = download.getUrl();
            String format = download.getFormat();
            String quality = download.getQuality();
            String savePath = download.getSavePath();

            // Validar entradas
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("URL não pode ser vazia");
            }
            if (format == null || (!format.equals("mp3") && !format.equals("mp4"))) {
                throw new IllegalArgumentException("Formato inválido: " + format);
            }
            if (savePath == null || savePath.trim().isEmpty()) {
                savePath = "output." + format;
            }

            // Sanitizar e normalizar o savePath
            File saveDir = new File(defaultOutputDir);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }
            String outputFile = new File(saveDir, savePath).getAbsolutePath();
            logger.debug("Caminho de saída normalizado: {}", outputFile);

            // Verificar se yt-dlp e ffmpeg existem
            File ytDlpFile = new File(ytDlpPath);
            File ffmpegFile = new File(ffmpegPath);
            if (!ytDlpFile.exists()) {
                logger.error("yt-dlp não encontrado em: {}", ytDlpPath);
                throw new RuntimeException("yt-dlp não encontrado em: " + ytDlpPath);
            }
            if (!ffmpegFile.exists()) {
                logger.error("ffmpeg não encontrado em: {}", ffmpegPath);
                throw new RuntimeException("ffmpeg não encontrado em: " + ffmpegPath);
            }

            // Validar permissões do diretório
            if (!saveDir.isDirectory()) {
                logger.error("Caminho não é um diretório válido: {}", saveDir.getAbsolutePath());
                throw new RuntimeException("Caminho não é um diretório válido: " + saveDir.getAbsolutePath());
            }
            if (!saveDir.canWrite()) {
                logger.error("Sem permissão de escrita no diretório: {}", saveDir.getAbsolutePath());
                throw new RuntimeException("Sem permissão de escrita no diretório: " + saveDir.getAbsolutePath());
            }

            // Verificar espaço em disco
            long freeSpace = saveDir.getFreeSpace();
            long requiredSpace = 2_000_000_000L; // 2 GB
            if (freeSpace < requiredSpace) {
                logger.error("Espaço insuficiente: {} MB disponível, {} MB necessário", freeSpace / 1_000_000, requiredSpace / 1_000_000);
                throw new RuntimeException("Espaço insuficiente no diretório: " + saveDir.getAbsolutePath());
            }

            // Limpar arquivos .part
            File partFile = new File(saveDir, savePath + ".part");
            if (partFile.exists() && !partFile.delete()) {
                logger.warn("Não foi possível excluir arquivo .part: {}", partFile.getAbsolutePath());
            }

            // Construir o comando
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
                    default -> "b";
                };
                command.add("-f");
                command.add(qualityFilter);
            }
            command.add("-o");
            command.add(outputFile);
            command.add(url);

            logger.info("Comando construído: {}", String.join(" ", command));

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

                boolean finished = process.waitFor(30, TimeUnit.MINUTES);
                outputReader.join(1000);

                if (!finished) {
                    process.destroyForcibly();
                    logger.error("Download demorou muito e foi cancelado");
                    throw new RuntimeException("Download demorou muito e foi cancelado. Saída: " + output);
                }

                int exitCode = process.exitValue();
                logger.info("Código de saída: {}", exitCode);
                logger.debug("Saída do yt-dlp: {}", output);

                if (exitCode != 0) {
                    logger.error("Erro ao baixar o arquivo. Código de saída: {}", exitCode);
                    throw new RuntimeException("Erro ao baixar o arquivo. Saída: " + output);
                }

                File file = new File(outputFile);
                if (!file.exists()) {
                    logger.error("Arquivo não foi criado: {}", outputFile);
                    throw new RuntimeException("Arquivo não foi criado: " + outputFile);
                }

                download.setFilePath(outputFile);
                logger.info("Salvando download no banco: {}", download);
                downloadRepository.save(download);
                logger.info("Download salvo com ID: {}", download.getId());

                Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                        .body(resource);
            } catch (Exception e) {
                logger.error("Erro durante o processamento do download: {}", e.getMessage(), e);
                throw new RuntimeException("Erro durante o processamento do download: " + e.getMessage(), e);
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            logger.error("Erro ao processar download: {}", e.getMessage(), e);
            throw new RuntimeException("Erro ao processar o download: " + e.getMessage(), e);
        }
    }

    @GetMapping("/download-file")
    public ResponseEntity<Resource> downloadFile(@RequestParam("filePath") String filePath) throws IOException {
        logger.info("Solicitação para baixar arquivo: {}", filePath);
        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("Arquivo não encontrado: {}", filePath);
            throw new RuntimeException("Arquivo não encontrado: " + filePath);
        }

        Resource resource = new FileSystemResource(file);
        logger.info("Enviando arquivo: {}, tamanho: {} bytes", file.getName(), file.length());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }

    @GetMapping("/test-save")
    public String testSave(Model model) {
        try {
            logger.info("Testando salvamento no banco de dados");
            Download download = new Download();
            download.setUrl("https://www.youtube.com/watch?v=test");
            download.setFormat("mp4");
            download.setQuality("720p");
            download.setSavePath(defaultOutputDir + "/test.mp4");
            logger.info("Salvando download de teste no banco...");
            downloadRepository.save(download);
            logger.info("Download de teste salvo com ID: {}", download.getId());
            model.addAttribute("message", "Registro salvo no banco com ID: " + download.getId());
            return "index";
        } catch (Exception e) {
            logger.error("Erro ao salvar no banco: {}", e.getMessage(), e);
            model.addAttribute("errorMessage", "Erro ao salvar no banco: " + e.getMessage());
            return "index";
        }
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
            throw new RuntimeException("Erro ao listar downloads: " + e.getMessage(), e);
        }
    }
}