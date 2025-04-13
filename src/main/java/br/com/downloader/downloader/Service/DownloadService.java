package br.com.downloader.downloader.Service;

import br.com.downloader.downloader.model.Download;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DownloadService {

    @Value("${yt-dlp.path}")
    private String ytDlpPath;

    @Value("${download.output.dir}")
    private String outputDir;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;
/*
    @PostConstruct
    public void validatePaths() {
        if (!new File(ytDlpPath).exists()) {
            throw new IllegalStateException("yt-dlp não encontrado: " + ytDlpPath);
        }
        if (!new File(ffmpegPath).exists()) {
            throw new IllegalStateException("ffmpeg não encontrado: " + ffmpegPath);
        }
        if (!new File(outputDir).exists() && !new File(outputDir).mkdirs()) {
            throw new IllegalStateException("Não conseguiu criar diretório: " + outputDir);
        }
    }
*/
    public File downloadFile(Download download) throws IOException, InterruptedException {
        String url = download.getUrl();
        String format = download.getFormat();
        String outputFile = outputDir + File.separator + "output_" + System.currentTimeMillis() + "." + format;

        List<String> command = format.equals("mp3")
                ? Arrays.asList(ytDlpPath, "--extract-audio", "--audio-format", "mp3", "--ffmpeg-location", ffmpegPath, "-o", outputFile, url)
                : Arrays.asList(ytDlpPath, "-f", "best", "--ffmpeg-location", ffmpegPath, "-o", outputFile, url);

        System.out.println("Comando executado: " + String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(outputDir));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("yt-dlp output: " + line);
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Download demorou muito: " + output);
        }

        int exitCode = process.exitValue();
        System.out.println("Exit code do yt-dlp: " + exitCode);
        System.out.println("Saída completa do yt-dlp: " + output);

        if (exitCode != 0) {
            throw new RuntimeException("Erro no yt-dlp, exit code " + exitCode + ": " + output);
        }

        File file = new File(outputFile);
        if (!file.exists()) {
            throw new RuntimeException("Arquivo não criado: " + outputFile);
        }

        download.setFilePath(file.getAbsolutePath());
        System.out.println("Download atualizado: URL=" + download.getUrl() + ", Format=" + download.getFormat() + ", FilePath=" + download.getFilePath());
        return file;
    }
}