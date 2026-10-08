package dev.supavolt.api.infrastructure.persistence;

import dev.supavolt.api.config.SupavoltProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Component;

/**
 * Encrypts per-project secrets at rest with AES-256-GCM (Spring Security Crypto), with a random
 * IV per value. The rest of the code sees plaintext. This replaces ASP.NET Data Protection's
 * {@code Supavolt.ProjectSecrets.v1} protector.
 */
@Component
public class SecretProtector {

    private static final Logger log = LoggerFactory.getLogger(SecretProtector.class);

    private final BytesEncryptor encryptor;

    public SecretProtector(SupavoltProperties properties) {
        // The configured key is 32+ random characters, so a single SHA-256 is a sound AES-256 key
        // derivation; a password-stretching KDF would add nothing.
        this.encryptor = AesGcmBytesEncryptor.withSecretKey(aesKey(properties.secrets().encryptionKey())).build();
    }

    public String protect(String plaintext) {
        if (plaintext == null) return null;
        return Base64.getEncoder().encodeToString(encryptor.encrypt(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Null when the value cannot be decrypted: written by the .NET API's Data Protection keys, or
     * under a different encryption key. Callers treat that as "not set" and recover — see
     * DECISIONS.md — rather than failing every request that loads the project.
     */
    public String unprotect(String ciphertext) {
        if (ciphertext == null) return null;

        try {
            return new String(encryptor.decrypt(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.warn("A stored secret could not be decrypted and is treated as unset");
            return null;
        }
    }

    private static SecretKeySpec aesKey(String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
