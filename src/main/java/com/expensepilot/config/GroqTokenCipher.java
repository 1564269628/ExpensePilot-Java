package com.expensepilot.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for the local Groq API token.
 *
 * <p>Format: "v1:" + Base64(16-byte salt || 12-byte nonce || ciphertext+tag).
 * A 200,000-round PBKDF2-HMAC-SHA256 key is derived from a passphrase.
 *
 * <p>Warning: When the ciphertext and its password are both committed to a
 * public repository, this provides obfuscation only, NOT secret protection.
 */
public final class GroqTokenCipher {

    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private GroqTokenCipher() {
    }

    public static String encrypt(String plaintext, String password) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Groq token must not be empty");
        }
        requirePassword(password);

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(TAG_BITS, nonce));

            byte[] sealed = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8));

            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(SALT_BYTES + NONCE_BYTES + sealed.length)
                            .put(salt)
                            .put(nonce)
                            .put(sealed)
                            .array());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot encrypt Groq token", ex);
        }
    }

    public static String decrypt(String encrypted, String password) {
        requirePassword(password);
        if (encrypted == null || !encrypted.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Invalid encrypted Groq token format");
        }

        final byte[] payload;
        try {
            payload = Base64.getDecoder().decode(
                    encrypted.substring(PREFIX.length()).trim());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid encrypted Groq token encoding");
        }

        if (payload.length < SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Encrypted Groq token is truncated");
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] sealed = new byte[buffer.remaining() - SALT_BYTES - NONCE_BYTES];
        buffer.get(salt);
        buffer.get(nonce);
        buffer.get(sealed);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException ex) {
            // Never include encrypted or plaintext token in an error message.
            throw new IllegalStateException(
                    "Cannot decrypt Groq token: check the password and ciphertext", ex);
        }
    }

    private static SecretKeySpec deriveKey(
            String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        }
        finally {
            spec.clearPassword();
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Groq token password must not be empty");
        }
    }
}
