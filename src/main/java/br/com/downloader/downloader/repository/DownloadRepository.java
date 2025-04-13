package br.com.downloader.downloader.repository;

import br.com.downloader.downloader.model.Download;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadRepository extends JpaRepository<Download, Long> {
}