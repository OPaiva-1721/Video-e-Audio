package br.com.downloader.downloader.controller;

import br.com.downloader.downloader.Service.DownloadService;
import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import br.com.downloader.downloader.websocket.DownloadProgressMessage;
import br.com.downloader.downloader.websocket.ProgressNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@CrossOrigin(origins = "https://front-video-e-audio-4xzy.vercel.app/")
@RestController
@RequestMapping("/api")
public class DownloadController {
    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final long REQUEST_LIMIT = 5; // Aumentado para 5 requisições simultâneas
    private static final long DELAY_MS = 10000; // Reduzido para 10 segundos entre requisições
    private static long lastRequestTime = 0;

    private final DownloadRepository downloadRepository;
    private final DownloadService downloadService;
    private final ProgressNotificationService notificationService;

    @Value("${download.output.dir:/app/downloads}")
    private String defaultOutputDir;

    @Autowired
    public DownloadController(
            DownloadRepository downloadRepository,
            DownloadService downloadService,
            ProgressNotificationService notificationService) {
        this.downloadRepository = downloadRepository;
        this.downloadService = downloadService;
        this.notificationService = notificationService;
    }

    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody Download download) throws InterruptedException {
        synchronized (DownloadController.class) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRequestTime < DELAY_MS) {
                long waitTime = DELAY_MS - (currentTime - lastRequestTime);
                logger.info("Aguardando {} ms para respeitar o limite de requisições", waitTime);
                Thread.sleep(waitTime);
            }

            int currentCount = requestCount.incrementAndGet();
            try {
                if (currentCount > REQUEST_LIMIT) {
                    logger.warn("Limite de requisições atingido: {}", currentCount);
                    return ResponseEntity.status(429).body("Limite de requisições atingido. Tente novamente em alguns segundos.");
                }

                logger.info("Iniciando download: {}", download);
                String url = download.getUrl();
                String format = download.getFormat();
                String quality = download.getQuality();
                String savePath = download.getSavePath();

                if (url == null || url.trim().isEmpty()) {
                    return ResponseEntity.badRequest().body("URL não pode ser vazia");
                }
                
                // Validar formatos suportados
                if (format == null || (!format.equals("mp3") && !format.equals("mp4") && 
                                      !format.equals("webm") && !format.equals("ogg") && 
                                      !format.equals("flac"))) {
                    return ResponseEntity.badRequest().body("Formato inválido: " + format);
                }
                
                if (savePath == null || savePath.trim().isEmpty()) {
                    savePath = "output." + format;
                }

                File saveDir = new File(defaultOutputDir);
                if (!saveDir.exists()) {
                    saveDir.mkdirs();
                }

                // Verificar espaço em disco
                long freeSpace = saveDir.getFreeSpace();
                long requiredSpace = 2_000_000_000L;
                if (freeSpace < requiredSpace) {
                    logger.error("Espaço insuficiente: {} MB disponível, {} MB necessário", freeSpace / 1_000_000, requiredSpace / 1_000_000);
                    return ResponseEntity.status(500).body("Espaço insuficiente no diretório: " + saveDir.getAbsolutePath());
                }

                // Inicializar o download no banco de dados
                download.setStatus("Iniciado");
                download.setProgress(0);
                downloadRepository.save(download);
                
                // Notificar início do download via WebSocket
                DownloadProgressMessage progressMessage = new DownloadProgressMessage(
                    download.getId(),
                    download.getUrl(),
                    download.getFormat(),
                    download.getQuality(),
                    download.getStatus(),
                    0,
                    download.getFileName(),
                    download.getFilePath()
                );
                notificationService.notifyDownloadStarted(progressMessage);

                // Iniciar download assíncrono
                CompletableFuture.runAsync(() -> {
                    try {
                        downloadService.downloadFileAsync(download);
                    } catch (Exception e) {
                        logger.error("Erro durante download assíncrono: {}", e.getMessage(), e);
                        download.setStatus("Erro");
                        downloadRepository.save(download);
                        
                        // Notificar erro via WebSocket
                        DownloadProgressMessage errorMessage = new DownloadProgressMessage(
                            download.getId(),
                            download.getUrl(),
                            download.getFormat(),
                            download.getQuality(),
                            "Erro",
                            0,
                            download.getFileName(),
                            download.getFilePath()
                        );
                        notificationService.notifyError(errorMessage);
                    }
                });

                return ResponseEntity.ok().body("{\"message\": \"Download started\", \"id\": \"" + download.getId() + "\"}");
            } catch (Exception e) {
                logger.error("Erro ao processar download: {}", e.getMessage(), e);
                return ResponseEntity.status(500).body("Erro ao processar o download: " + e.getMessage());
            } finally {
                lastRequestTime = System.currentTimeMillis();
                // Resetar contador após um tempo
                new Thread(() -> {
                    try {
                        Thread.sleep(DELAY_MS);
                        requestCount.decrementAndGet();
                    } catch (InterruptedException e) {
                        logger.error("Erro no reset do contador: {}", e.getMessage());
                    }
                }).start();
            }
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
    
    @GetMapping("/download/{id}")
    public ResponseEntity<Download> getDownload(@PathVariable Long id) {
        try {
            return downloadRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Erro ao buscar download: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        }
    }
}
