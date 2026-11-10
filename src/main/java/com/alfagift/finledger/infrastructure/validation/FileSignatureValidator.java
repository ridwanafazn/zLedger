package com.alfagift.finledger.infrastructure.validation;

/**
 * Validasi "magic bytes" agar tipe file yang diklaim klien tidak bisa dipalsukan
 * hanya dengan mengubah header {@code Content-Type}.
 */
public final class FileSignatureValidator {

    private FileSignatureValidator() {
    }

    /**
     * Memeriksa apakah isi file sesuai dengan MIME type yang diklaim.
     *
     * @return {@code true} bila signature cocok atau tipe tidak memerlukan pemeriksaan
     */
    public static boolean matches(String contentType, byte[] content) {
        if (contentType == null || content == null || content.length < 4) {
            return false;
        }
        return switch (contentType) {
            case "image/png" -> startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "application/pdf" -> startsWith(content, new byte[]{'%', 'P', 'D', 'F'});
            case "image/webp" -> startsWith(content, new byte[]{'R', 'I', 'F', 'F'})
                    && content.length > 12
                    && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
