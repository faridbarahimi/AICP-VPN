package com.v2ray.ang.util

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupEncryptionTest {
    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun encryptedBackupRoundTripsWithAuthenticatedHeader() {
        val dir = Files.createTempDirectory("vpn-backup-encryption").toFile()
        try {
            val source = File(dir, "plain.zip").apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
            val encrypted = File(dir, "backup.aicpvpn")
            val restored = File(dir, "restored.zip")
            BackupEncryption.encryptFile(source, encrypted, password)
            assertTrue(BackupEncryption.isEncrypted(encrypted))
            BackupEncryption.decryptFile(encrypted, restored, password)
            assertArrayEquals(source.readBytes(), restored.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wrongPasswordAndTamperingAreRejected() {
        val dir = Files.createTempDirectory("vpn-backup-auth").toFile()
        try {
            val source = File(dir, "plain.zip").apply { writeText("vpn-secret-config") }
            val encrypted = File(dir, "backup.aicpvpn")
            val restored = File(dir, "restored.zip")
            BackupEncryption.encryptFile(source, encrypted, password)
            assertThrows(Exception::class.java) {
                BackupEncryption.decryptFile(encrypted, restored, "incorrect password".toCharArray())
            }
            assertTrue(encrypted.exists(), "A failed restore must not destroy the user's source backup")
            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            encrypted.writeBytes(bytes)
            assertThrows(Exception::class.java) {
                BackupEncryption.decryptFile(encrypted, restored, password)
            }
            assertFalse(restored.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsWeakPasswordsAndUnencryptedFilesAreRecognized() {
        val dir = Files.createTempDirectory("vpn-backup-policy").toFile()
        try {
            val source = File(dir, "plain.zip").apply { writeText("data") }
            val encrypted = File(dir, "backup.aicpvpn")
            assertThrows(Exception::class.java) {
                BackupEncryption.encryptFile(source, encrypted, "short".toCharArray())
            }
            assertFalse(BackupEncryption.isEncrypted(source))
        } finally {
            dir.deleteRecursively()
        }
    }
}
