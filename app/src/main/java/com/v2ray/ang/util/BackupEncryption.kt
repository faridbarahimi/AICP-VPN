package com.v2ray.ang.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based, portable AES-256-GCM envelope for VPN backups. */
object BackupEncryption {
    private val MAGIC = "AICPVPNB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    const val MIN_PASSWORD_LENGTH = 12
    const val MAX_ENCRYPTED_BYTES = ZipUtil.MAX_ARCHIVE_BYTES + 1024L

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(MAGIC.size)
                readFully(input, header)
                header.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    @Throws(IOException::class)
    fun encryptFile(source: File, destination: File, password: CharArray) {
        if (password.size < MIN_PASSWORD_LENGTH) throw IOException("Backup password is too short")
        if (!source.isFile || source.length() > ZipUtil.MAX_ARCHIVE_BYTES) {
            throw IOException("Backup archive is missing or too large")
        }
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Backup destination has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create backup directory")
        val temp = File.createTempFile("vpn-backup-enc-", ".part", parent)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + NONCE_BYTES)
            .put(MAGIC)
            .putInt(PBKDF2_ITERATIONS)
            .put(salt)
            .put(nonce)
            .array()
        try {
            val keyBytes = deriveKey(password, salt, PBKDF2_ITERATIONS)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(header)
                FileOutputStream(temp).use { fileOutput ->
                    fileOutput.write(header)
                    CipherOutputStream(fileOutput, cipher).use { encrypted ->
                        FileInputStream(source).use { input -> copyBounded(input, encrypted, ZipUtil.MAX_ARCHIVE_BYTES) }
                    }
                }
            } finally {
                keyBytes.fill(0)
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            if (temp.length() > MAX_ENCRYPTED_BYTES) throw IOException("Encrypted backup exceeds the size limit")
            moveAtomically(temp, destination)
        } catch (error: Exception) {
            temp.delete()
            if (error is IOException) throw error
            throw IOException("Could not encrypt backup (${error.javaClass.simpleName})", error)
        } finally {
            salt.fill(0)
            nonce.fill(0)
        }
    }

    @Throws(IOException::class)
    fun decryptFile(source: File, destination: File, password: CharArray) {
        if (password.isEmpty()) throw IOException("Backup password is required")
        if (!source.isFile || source.length() > MAX_ENCRYPTED_BYTES) {
            throw IOException("Encrypted backup is missing or too large")
        }
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Backup destination has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create restore directory")
        val temp = File.createTempFile("vpn-backup-dec-", ".part", parent)
        try {
            FileInputStream(source).use { input ->
                val headerSize = MAGIC.size + 4 + SALT_BYTES + NONCE_BYTES
                val header = ByteArray(headerSize)
                readFully(input, header)
                val buffer = ByteBuffer.wrap(header)
                val magic = ByteArray(MAGIC.size).also(buffer::get)
                if (!magic.contentEquals(MAGIC)) throw IOException("Unsupported backup format")
                val iterations = buffer.int
                if (iterations !in 100_000..1_000_000) throw IOException("Unsupported backup KDF parameters")
                val salt = ByteArray(SALT_BYTES).also(buffer::get)
                val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
                val keyBytes = deriveKey(password, salt, iterations)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                    cipher.updateAAD(header)
                    CipherInputStream(input, cipher).use { decrypted ->
                        FileOutputStream(temp).use { output ->
                            copyBounded(decrypted, output, ZipUtil.MAX_ARCHIVE_BYTES)
                            output.fd.sync()
                        }
                    }
                } finally {
                    keyBytes.fill(0)
                    salt.fill(0)
                    nonce.fill(0)
                }
            }
            moveAtomically(temp, destination)
        } catch (error: Exception) {
            temp.delete()
            if (error is IOException) throw IOException("Backup authentication/decryption failed", error)
            throw IOException("Backup authentication/decryption failed (${error.javaClass.simpleName})", error)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > maxBytes - total) throw IOException("Backup payload exceeds the size limit")
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun readFully(input: FileInputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("Backup header is truncated")
            offset += count
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
