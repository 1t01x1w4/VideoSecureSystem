package com.vsec.crypto;

import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.digests.SM3Digest;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

public final class CryptoUtil {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SALT_LEN = 32;
    private static final int DEK_LEN = 16;
    private static final int IV_LEN = 16;
    private static final int AUTH_KEY_LEN = 32;
    private static final int ENC_KEY_LEN = 16;

    // ==================== Argon2id + HKDF 密钥派生 ====================

    /**
     * 生成 32 字节随机盐值
     */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LEN];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * 从口令派生 auth_key + enc_key（enc_key 不落盘）
     */
    public static DerivedKeys deriveKeys(String password, byte[] salt,
                                          int memoryKb, int iterations, int parallelism) {
        byte[] master = argon2id(password.toCharArray(), salt, memoryKb, iterations,
                parallelism, AUTH_KEY_LEN + ENC_KEY_LEN);

        // HKDF-Extract: 用 salt=0 的信息扩展
        byte[] prk = hmacSM3(new byte[32], master);
        // HKDF-Expand: 派生 auth_key
        byte[] authKey = hkdfExpand(prk, "auth-key".getBytes(StandardCharsets.UTF_8), AUTH_KEY_LEN);
        // HKDF-Expand: 派生 enc_key (MK)
        byte[] encKey = hkdfExpand(prk, "enc-key".getBytes(StandardCharsets.UTF_8), ENC_KEY_LEN);

        // 擦除中间物
        Arrays.fill(master, (byte) 0);
        Arrays.fill(prk, (byte) 0);

        return new DerivedKeys(authKey, encKey);
    }

    /**
     * 仅派生 auth_key（登录验证用）
     */
    public static byte[] deriveAuthKey(String password, byte[] salt,
                                        int memoryKb, int iterations, int parallelism) {
        DerivedKeys keys = deriveKeys(password, salt, memoryKb, iterations, parallelism);
        byte[] encKey = keys.encKey();
        byte[] authKey = keys.authKey();
        Arrays.fill(encKey, (byte) 0);
        return authKey;
    }

    // ==================== SM3 哈希 ====================

    public static byte[] sm3(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return hash;
    }

    public static byte[] sm3(byte[]... chunks) {
        SM3Digest digest = new SM3Digest();
        for (byte[] chunk : chunks) {
            digest.update(chunk, 0, chunk.length);
        }
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return hash;
    }

    public static byte[] sm3Stream(InputStream in) throws IOException {
        SM3Digest digest = new SM3Digest();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            digest.update(buf, 0, n);
        }
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return hash;
    }

    // ==================== SM4-CTR 加密/解密 ====================

    private static final int SM4_BLOCK = 16;

    /**
     * SM4-CTR 加密
     * @param key   16 字节密钥（DEK）
     * @param iv    16 字节初始向量
     * @param plaintext 明文
     * @return 密文
     */
    public static byte[] sm4CtrEncrypt(byte[] key, byte[] iv, byte[] plaintext) {
        return sm4CtrProcess(true, key, iv, plaintext, 0, plaintext.length);
    }

    /**
     * SM4-CTR 解密
     * @param key   16 字节密钥（DEK）
     * @param iv    16 字节初始向量
     * @param ciphertext 密文
     * @return 明文
     */
    public static byte[] sm4CtrDecrypt(byte[] key, byte[] iv, byte[] ciphertext) {
        return sm4CtrProcess(false, key, iv, ciphertext, 0, ciphertext.length);
    }

    /**
     * SM4-CTR 按偏移量解密（支持 Range 请求的随机访问）
     */
    public static byte[] sm4CtrDecryptRange(byte[] key, byte[] iv, byte[] ciphertext,
                                             long offset, int length) {
        // CTR 计数器按块递增，从 offset 对应的块开始
        long blockOffset = offset / SM4_BLOCK;
        int skipBytes = (int) (offset % SM4_BLOCK);

        // 调整 IV 以匹配偏移块
        byte[] adjustedIv = adjustCounter(iv, blockOffset);
        byte[] raw = sm4CtrProcess(false, key, adjustedIv, ciphertext,
                (int) (offset - skipBytes), length + skipBytes);

        return Arrays.copyOfRange(raw, skipBytes, raw.length);
    }

    /**
     * 解密从磁盘读取的块对齐密文切片（用于 Range 请求的高效解密，无需加载全文件）
     * @param key         DEK (16 bytes)
     * @param iv          原始 CTR IV
     * @param ciphertext  从块对齐位置读取的密文切片
     * @param blockOffset 切片起始位置对应的块号 (absolutePosition / 16)
     * @param skipLeading 解密后跳过的前导字节数 (absolutePosition % 16)
     * @param resultLen   最终返回的明文字节数
     */
    public static byte[] sm4CtrDecryptAligned(byte[] key, byte[] iv, byte[] ciphertext,
                                               long blockOffset, int skipLeading, int resultLen) {
        byte[] adjustedIv = adjustCounter(iv, blockOffset);
        byte[] raw = sm4CtrProcess(false, key, adjustedIv, ciphertext, 0, ciphertext.length);
        byte[] result = new byte[resultLen];
        System.arraycopy(raw, skipLeading, result, 0, resultLen);
        return result;
    }

    private static byte[] sm4CtrProcess(boolean encrypt, byte[] key, byte[] iv,
                                         byte[] data, int dataOffset, int dataLen) {
        try {
            Cipher cipher = Cipher.getInstance("SM4/CTR/NoPadding", "BC");
            SecretKeySpec keySpec = new SecretKeySpec(key, "SM4");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(data, dataOffset, dataLen);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("SM4-CTR operation failed", e);
        }
    }

    public static byte[] adjustCounter(byte[] iv, long blockOffset) {
        BigInteger bi = new BigInteger(1, iv).add(BigInteger.valueOf(blockOffset));
        byte[] result = bi.toByteArray();
        if (result.length == 16) return result;
        byte[] padded = new byte[16];
        int srcPos = result.length > 16 ? result.length - 16 : 0;
        int destPos = result.length > 16 ? 0 : 16 - result.length;
        int copyLen = Math.min(result.length, 16);
        System.arraycopy(result, srcPos, padded, destPos, copyLen);
        return padded;
    }

    // ==================== SM4-CBC（用于 DEK 加密与服务端密钥保护）====================

    /**
     * 用 MK 加密 DEK
     */
    public static byte[] encryptDek(byte[] mk, byte[] dek, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("SM4/CBC/PKCS7Padding", "BC");
            SecretKeySpec keySpec = new SecretKeySpec(mk, "SM4");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(dek);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("DEK encryption failed", e);
        }
    }

    /**
     * 用 MK 解密 DEK
     */
    public static byte[] decryptDek(byte[] mk, byte[] encryptedDek, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("SM4/CBC/PKCS7Padding", "BC");
            SecretKeySpec keySpec = new SecretKeySpec(mk, "SM4");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(encryptedDek);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("DEK decryption failed", e);
        }
    }

    // ==================== 随机密钥生成 ====================

    public static byte[] generateDek() {
        byte[] dek = new byte[DEK_LEN];
        SECURE_RANDOM.nextBytes(dek);
        return dek;
    }

    public static byte[] generateIv() {
        byte[] iv = new byte[IV_LEN];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    // ==================== 内部方法 ====================

    private static byte[] argon2id(char[] password, byte[] salt,
                                    int memoryKb, int iterations, int parallelism, int outputLen) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withParallelism(parallelism)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] output = new byte[outputLen];
        // 密码转换为 UTF-8 字节
        byte[] pwdBytes = toBytes(password);
        generator.generateBytes(pwdBytes, output);
        Arrays.fill(pwdBytes, (byte) 0);
        return output;
    }

    public static byte[] hmacSM3(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SM3Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] result = new byte[hmac.getMacSize()];
        hmac.doFinal(result, 0);
        return result;
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] t = new byte[0];
        byte[] result = new byte[length];
        int remaining = length;
        int offset = 0;
        int i = 1;

        while (remaining > 0) {
            HMac hmac = new HMac(new SM3Digest());
            hmac.init(new KeyParameter(prk));
            hmac.update(t, 0, t.length);
            hmac.update(info, 0, info.length);
            hmac.update((byte) i);
            t = new byte[hmac.getMacSize()];
            hmac.doFinal(t, 0);

            int copyLen = Math.min(remaining, t.length);
            System.arraycopy(t, 0, result, offset, copyLen);
            offset += copyLen;
            remaining -= copyLen;
            i++;
        }
        return result;
    }

    private static byte[] toBytes(char[] chars) {
        byte[] bytes = new byte[chars.length * 3]; // UTF-8 max 3 bytes per char
        int len = 0;
        for (char c : chars) {
            if (c <= 0x7F) {
                bytes[len++] = (byte) c;
            } else if (c <= 0x7FF) {
                bytes[len++] = (byte) (0xC0 | (c >> 6));
                bytes[len++] = (byte) (0x80 | (c & 0x3F));
            } else {
                bytes[len++] = (byte) (0xE0 | (c >> 12));
                bytes[len++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                bytes[len++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        return Arrays.copyOf(bytes, len);
    }

    /**
     * 用服务端密钥加密 enc_key（防 Redis 泄密）
     * @param data  待保护的密钥数据
     * @param secret  服务端密钥字符串
     * @return IV + 密文
     */
    public static byte[] encryptWithServerKey(byte[] data, String secret) {
        byte[] sk = deriveFixedKey(secret);
        byte[] iv = generateIv();
        byte[] enc = encryptDek(sk, data, iv);
        byte[] result = new byte[IV_LEN + enc.length];
        System.arraycopy(iv, 0, result, 0, IV_LEN);
        System.arraycopy(enc, 0, result, IV_LEN, enc.length);
        wipe(sk);
        return result;
    }

    /**
     * 用服务端密钥解密 enc_key
     * @param wrapped  IV + 密文
     * @param secret  服务端密钥字符串
     * @return 原始密钥数据
     */
    public static byte[] decryptWithServerKey(byte[] wrapped, String secret) {
        byte[] sk = deriveFixedKey(secret);
        byte[] iv = Arrays.copyOf(wrapped, IV_LEN);
        byte[] enc = Arrays.copyOfRange(wrapped, IV_LEN, wrapped.length);
        byte[] dec = decryptDek(sk, enc, iv);
        wipe(sk);
        return dec;
    }

    private static byte[] deriveFixedKey(String secret) {
        byte[] hash = new byte[32];
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        SM3Digest digest = new SM3Digest();
        digest.update(bytes, 0, bytes.length);
        digest.doFinal(hash, 0);
        return Arrays.copyOf(hash, DEK_LEN);
    }

    /**
     * 常量时间比较（防时序攻击）
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /**
     * 安全擦除字节数组
     */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    // ==================== 结果封装 ====================

    public record DerivedKeys(byte[] authKey, byte[] encKey) {}
}
