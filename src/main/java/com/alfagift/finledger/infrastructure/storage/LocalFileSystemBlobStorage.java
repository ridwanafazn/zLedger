package com.alfagift.finledger.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Penyimpanan blob pada filesystem lokal (default untuk dev & test tanpa Docker).
 * <p>
 * Key divalidasi agar tidak bisa keluar dari direktori dasar (path traversal),
 * dan penulisan memakai file sementara + atomic move supaya objek tidak pernah
 * terlihat setengah jadi.
 */
@Slf4j
public class LocalFileSystemBlobStorage implements BlobStorage {

    private final Path basePath;

    public LocalFileSystemBlobStorage(Path basePath) {
        this.basePath = basePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException ex) {
            throw new BlobStorageException("Tidak dapat membuat direktori blob: " + this.basePath, ex);
        }
        log.info("Blob storage lokal aktif di {}", this.basePath);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            if (size > 0 && written != size) {
                throw new BlobStorageException(
                        String.format("Ukuran blob tidak sesuai: diharapkan %d, ditulis %d", size, written), null);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new BlobStorageException("Gagal menyimpan blob " + key, ex);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public InputStream get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BlobNotFoundException(key);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw new BlobStorageException("Gagal membaca blob " + key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new BlobStorageException("Gagal menghapus blob " + key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public Optional<String> presignedDownloadUrl(String key, Duration ttl) {
        // Storage lokal tidak mengekspos URL langsung; aplikasi melakukan streaming.
        return Optional.empty();
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("Object key blob tidak valid: " + key);
        }
        Path resolved = basePath.resolve(key).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Object key blob keluar dari direktori dasar: " + key);
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Gagal menghapus file sementara {}: {}", path, ex.getMessage());
        }
    }
}
