package br.com.downloader.downloader.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadProgressMessage {
    private Long id;
    private String url;
    private String format;
    private String quality;
    private String status;
    private int progress;
    private String fileName;
    private String filePath;
}
