package br.com.downloader.downloader.controller;

import br.com.downloader.downloader.model.Download;
import br.com.downloader.downloader.repository.DownloadRepository;
import br.com.downloader.downloader.Service.DownloadService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class DownloadController {

    @Autowired
    private DownloadRepository downloadRepository;

    @Autowired
    private DownloadService DownloadService;

    @PostConstruct
    public void init() {
        System.out.println("DownloadController inicializado!");
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("download", new Download());
        return "index";
    }

    @PostMapping("/download")
    @Transactional
    public String download(@ModelAttribute Download download, Model model) {
        try {
            System.out.println("Antes do service: URL=" + download.getUrl() + ", Format=" + download.getFormat() + ", FilePath=" + download.getFilePath());
            File file = DownloadService.downloadFile(download);
            System.out.println("Depois do service: URL=" + download.getUrl() + ", Format=" + download.getFormat() + ", FilePath=" + download.getFilePath());
            if (download.getUrl() == null || download.getFormat() == null || download.getFilePath() == null) {
                throw new RuntimeException("Algum campo tá null: URL=" + download.getUrl() + ", Format=" + download.getFormat() + ", FilePath=" + download.getFilePath());
            }
            Download savedDownload = downloadRepository.saveAndFlush(download);
            System.out.println("Salvo no banco com ID: " + savedDownload.getId());
            return "redirect:/download-file?filePath=" + URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            System.out.println("Erro no download: " + e.getMessage());
            e.printStackTrace();
            model.addAttribute("errorMessage", "Deu merda no download: " + e.getMessage());
            return "index";
        }
    }

    @GetMapping("/download-file")
    public ResponseEntity<Resource> downloadFile(@RequestParam("filePath") String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("Arquivo sumiu: " + filePath);
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
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

    @GetMapping("/test-save")
    public String testSave(Model model) {
        Download test = new Download();
        test.setUrl("https://www.youtube.com/watch?v=teste");
        test.setFormat("mp4");
        test.setFilePath("D:/test.mp4");
        System.out.println("Testando save...");
        downloadRepository.saveAndFlush(test);
        System.out.println("Salvo com ID: " + test.getId());
        model.addAttribute("message", "Teste salvo com ID: " + test.getId());
        return "index";
    }
}