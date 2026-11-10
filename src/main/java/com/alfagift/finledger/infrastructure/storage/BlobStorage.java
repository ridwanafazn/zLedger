package com.alfagift.finledger.infrastructure.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Port penyimpanan objek (blob) untuk bukti bayar dan aset lain.
 * <p>
 * Implementasi tersedia untuk filesystem lokal (dev/test tanpa Docker) dan
 * S3-compatible (MinIO lokal, AWS S3, GCS interop) untuk produksi — sesuai
 * ADR-005. Byte file tidak pernah disimpan di PostgreSQL.
 */
public interface BlobStorage {

    /**
     * Nama jenis storage untuk observability, mis. {@code local} atau {@code s3}.
     */
    String type();

    /**
     * Menyimpan objek secara atomik. Bila key sudah ada, isinya ditimpa.
     *
     * @param key         object key (relatif, tanpa leading slash)
     * @param content     isi file
     * @param size        ukuran byte (harus sama dengan isi)
     * @param contentType MIME type
     */
    void put(String key, InputStream content, long size, String contentType);

    /**
     * Membuka stream baca objek. Pemanggil bertanggung jawab menutup stream.
     *
     * @throws BlobNotFoundException bila objek tidak ada
     */
    InputStream get(String key);

    /**
     * Menghapus objek bila ada (idempoten).
     */
    void delete(String key);

    boolean exists(String key);

    /**
     * URL unduh sementara (presigned) bila storage mendukung. Storage lokal
     * mengembalikan {@link Optional#empty()} sehingga aplikasi melakukan streaming.
     */
    Optional<String> presignedDownloadUrl(String key, Duration ttl);

    /**
     * URL unggah sementara (presigned PUT) agar klien dapat mengirim file besar
     * langsung ke storage tanpa melewati aplikasi.
     * <p>
     * Storage lokal mengembalikan {@link Optional#empty()} — gunakan endpoint
     * multipart untuk storage lokal.
     *
     * @param checksumSha256Base64 checksum SHA-256 (base64) yang ikut ditandatangani;
     *                             bila null, checksum diverifikasi dengan membaca objek
     */
    Optional<PresignedUpload> presignedUploadUrl(String key, String contentType, long sizeBytes,
                                                 String checksumSha256Base64, Duration ttl);

    /**
     * Membaca metadata objek tanpa mengunduh isinya.
     *
     * @return metadata, atau {@link Optional#empty()} bila objek tidak ada
     */
    Optional<BlobStat> stat(String key);
}
