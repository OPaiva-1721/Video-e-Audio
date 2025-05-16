package br.com.downloader.downloader.controller;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private DownloadRepository downloadRepository;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("download", new Download());
        return "index";
    }

    @PostMapping("/download")
    public String download(@ModelAttribute Download download, Model model) {
        try {
            System.out.println("Iniciando download: " + download);
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
                throw new IllegalArgumentException("Caminho de salvamento não pode ser vazio");
            }

            // Sanitizar e normalizar o savePath
            File savePathFile = new File(savePath);
            String outputFile = savePathFile.getAbsolutePath();
            System.out.println("Caminho de saída normalizado: " + outputFile);

            String ytDlpPath = "C:\\Users\\GabryelPaivaNeves\\Documents\\yt-dlp.exe";
            File ytDlpFile = new File(ytDlpPath);
            if (!ytDlpFile.exists()) {
                throw new RuntimeException("yt-dlp.exe não encontrado em: " + ytDlpPath);
            }

            // Obter o diretório pai ou usar um padrão
            File saveDir = savePathFile.getParentFile();
            if (saveDir == null) {
                System.out.println("saveDir é null, usando diretório padrão: C:\\Users\\lidia\\Downloads");
                saveDir = new File("C:\\Users\\lidia\\Downloads");
                outputFile = new File(saveDir, savePathFile.getName()).getAbsolutePath();
                System.out.println("Novo caminho de saída: " + outputFile);
            }

            System.out.println("Diretório de salvamento: " + saveDir.getAbsolutePath());

            // Validar permissões do diretório
            if (!saveDir.exists()) {
                System.out.println("Criando diretório: " + saveDir.getAbsolutePath());
                boolean created = saveDir.mkdirs();
                if (!created) {
                    throw new RuntimeException("Falha ao criar diretório: " + saveDir.getAbsolutePath());
                }
            }
            if (!saveDir.isDirectory()) {
                throw new RuntimeException("Caminho não é um diretório válido: " + saveDir.getAbsolutePath());
            }
            if (!saveDir.canWrite()) {
                throw new RuntimeException("Sem permissão de escrita no diretório: " + saveDir.getAbsolutePath());
            }

            // Verificar e limpar arquivos .part travados
            File partFile = new File(saveDir, savePathFile.getName() + ".part");
            if (partFile.exists() && !partFile.delete()) {
                System.out.println("Aviso: Não foi possível excluir arquivo .part existente: " + partFile.getAbsolutePath());
            }

            // Construir o comando como lista de argumentos
            List<String> command = new ArrayList<>();
            command.add(ytDlpPath);
            if (format.equals("mp3")) {
                command.add("--extract-audio");
                command.add("--audio-format");
                command.add("mp3");
            } else {
                String qualityFilter = switch (quality != null ? quality : "best") {
                    case "360p" -> "bestvideo[height<=360]+bestaudio/best[height<=360]";
                    case "720p" -> "bestvideo[height<=720]+bestaudio/best[height<=720]";
                    case "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
                    default -> "b"; // Usar -f b pra suprimir o aviso
                };
                command.add("-f");
                command.add(qualityFilter);
            }
            command.add("-o");
            command.add(outputFile);
            command.add(url);

            System.out.println("Comando construído: " + String.join(" ", command));

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
                            output.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        output.append("Erro ao ler saída: ").append(e.getMessage()).append("\n");
                    }
                });
                outputReader.start();

                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                outputReader.join(1000);

                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("Download demorou muito e foi cancelado. Saída: " + output);
                }

                int exitCode = process.exitValue();
                System.out.println("Código de saída: " + exitCode);
                System.out.println("Saída do yt-dlp: " + output);

                if (exitCode != 0) {
                    throw new RuntimeException("Erro ao baixar o arquivo. Saída: " + output);
                }

                File file = new File(outputFile);
                if (!file.exists()) {
                    throw new RuntimeException("Arquivo não foi criado: " + outputFile);
                }

                download.setFilePath(outputFile);
                System.out.println("Salvando download no banco: " + download);
                downloadRepository.save(download);
                System.out.println("Download salvo com ID: " + download.getId());

                return "redirect:/download-file?filePath=" + URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8.toString());
            } catch (Exception e) {
                throw new RuntimeException("Erro durante o processamento do download: " + e.getMessage(), e);
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao processar download: " + e.getMessage());
            e.printStackTrace();
            model.addAttribute("errorMessage", "Erro ao processar o download: " + e.getMessage());
            model.addAttribute("download", new Download());
            return "index";
        }
    }

    @GetMapping("/download-file")
    public ResponseEntity<Resource> downloadFile(@RequestParam("filePath") String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("Arquivo não encontrado: " + filePath);
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }

    @GetMapping("/test-save")
    public String testSave(Model model) {
        try {
            Download download = new Download();
            download.setUrl("https://www.youtube.com/watch?v=test");
            download.setFormat("mp4");
            download.setQuality("720p");
            download.setSavePath("C:\\Users\\lidia\\Documents\\spring boot\\video-e-audio\\test.mp4");
            System.out.println("Salvando download de teste no banco...");
            downloadRepository.save(download);
            System.out.println("Download de teste salvo com ID: " + download.getId());
            model.addAttribute("message", "Registro salvo no banco com ID: " + download.getId());
            return "index";
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Erro ao salvar no banco: " + e.getMessage());
            return "index";
        }
    }

    @GetMapping("/downloads")
    public String listDownloads(Model model) {
        try {
            System.out.println("Iniciando busca de downloads no banco MySQL...");
            List<Download> downloads = downloadRepository.findAll();
            System.out.println("Downloads encontrados: " + downloads.size());
            model.addAttribute("downloads", downloads);
            return "downloads";
        } catch (Exception e) {
            System.err.println("Erro ao listar downloads: " + e.getMessage());
            e.printStackTrace();
            model.addAttribute("errorMessage", "Erro ao listar downloads: " + e.getMessage());
            return "index";
        }
    }
}