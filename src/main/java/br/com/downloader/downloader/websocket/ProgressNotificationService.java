package br.com.downloader.downloader.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProgressNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public ProgressNotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void notifyProgressUpdate(DownloadProgressMessage progressMessage) {
        messagingTemplate.convertAndSend("/topic/progress", progressMessage);
    }

    public void notifyDownloadStarted(DownloadProgressMessage progressMessage) {
        messagingTemplate.convertAndSend("/topic/downloads", progressMessage);
    }

    public void notifyDownloadCompleted(DownloadProgressMessage progressMessage) {
        messagingTemplate.convertAndSend("/topic/completed", progressMessage);
    }

    public void notifyError(DownloadProgressMessage progressMessage) {
        messagingTemplate.convertAndSend("/topic/errors", progressMessage);
    }
}
