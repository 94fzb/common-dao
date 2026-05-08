package com.hibegin.common.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public class PasswordHashUtils {

    public static final String PBKDF2_SHA256 = "pbkdf2_sha256";
    public static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int DEFAULT_ITERATIONS = 200000;
    public static final int DEFAULT_SALT_LENGTH = 16;
    public static final int DEFAULT_HASH_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern LEGACY_MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    public static String hash(String password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    public static String hash(String password, int iterations) {
        if (Objects.isNull(password) || password.isEmpty()) {
            return null;
        }
        byte[] salt = new byte[DEFAULT_SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password.toCharArray(), salt, iterations, DEFAULT_HASH_LENGTH);
        return PBKDF2_SHA256 + "$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    public static boolean matches(String password, String encodedPassword) {
        if (isEmpty(password) || isEmpty(encodedPassword)) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != 4 || !PBKDF2_SHA256.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isLegacyMd5(String encodedPassword) {
        return isNotEmpty(encodedPassword) && LEGACY_MD5_PATTERN.matcher(encodedPassword).matches();
    }

    public static boolean needsRehash(String encodedPassword) {
        if (isEmpty(encodedPassword)) {
            return false;
        }
        if (isLegacyMd5(encodedPassword)) {
            return true;
        }
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != 4 || !PBKDF2_SHA256.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < DEFAULT_ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int hashLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, hashLength * 8);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 hash error", e);
        }
    }
}
