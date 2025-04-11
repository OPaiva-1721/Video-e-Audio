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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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
        String url = download.getUrl();
        String format = download.getFormat();
        String outputFile = "output." + format;

        String ytDlpPath = "C:\\Users\\lidia\\Documents\\spring boot\\video-e-audio\\yt-dlp.exe";
        String command = format.equals("mp3")
                ? ytDlpPath + " --extract-audio --audio-format mp3 -o " + outputFile + " " + url
                : ytDlpPath + " -f best -o " + outputFile + " " + url;

        System.out.println("Executando comando: " + command);

        ProcessBuilder builder = new ProcessBuilder(command.split(" "));
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true);
        Process process = null;

        try {
            process = builder.start();

            // Consumir a saída do processo em uma thread separada
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

            // Aguardar o processo com timeout de 30 segundos
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            outputReader.join(1000); // Aguardar a thread de leitura por 1 segundo

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Download demorou muito e foi cancelado. Saída: " + output);
            }

            int exitCode = process.exitValue();
            System.out.println("Código de saída: " + exitCode);
            System.out.println("Saída do yt-dlp: " + output);

            // Salvar no banco independentemente do resultado do download
            download.setFilePath("C:\\Users\\lidia\\Documents\\spring boot\\video-e-audio\\" + outputFile);
            System.out.println("Salvando download no banco: " + download.getUrl() + ", " + download.getFormat());
            downloadRepository.save(download);
            System.out.println("Download salvo com ID: " + download.getId());

            // Verificar se o download foi bem-sucedido
            if (exitCode != 0) {
                throw new RuntimeException("Erro ao baixar o arquivo. Saída: " + output);
            }

            File file = new File(outputFile);
            if (!file.exists()) {
                throw new RuntimeException("Arquivo não foi criado: " + outputFile);
            }

            // Redirecionar para a rota de download do arquivo
            return "redirect:/download-file?filePath=" + URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Erro ao processar o download: " + e.getMessage());
            model.addAttribute("download", new Download());
            return "index";
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
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
            download.setFilePath("C:\\Users\\lidia\\Documents\\spring boot\\video-e-audio\\test.mp4");
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
            model.addAttribute("downloads", downloadRepository.findAll());
            return "downloads";
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Erro ao listar downloads: " + e.getMessage());
            return "index";
        }
    }
}