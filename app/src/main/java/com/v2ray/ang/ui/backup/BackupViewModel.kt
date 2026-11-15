package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import com.v2ray.ang.util.BackupEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupViewModel(application: Application) : BaseViewModel(application) {

    private val _webDavConfig = MutableStateFlow(MmkvManager.decodeWebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig?> = _webDavConfig.asStateFlow()

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        data class ExportLocal(val cachePath: String, val targetUri: Uri) : BackupViewModelEvent
        data class WebDavBackupDownloaded(val filePath: String) : BackupViewModelEvent
        data class RestoreSuccess(val filePath: String) : BackupViewModelEvent
        data class RestoreFailed(val filePath: String) : BackupViewModelEvent
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        MmkvManager.encodeWebDavConfig(config)
        _webDavConfig.value = config
        toastSuccess(R.string.toast_success)
    }

    fun cleanupProfileStorage() {
        launchLoading {
            try {
                val removed = withContext(Dispatchers.IO) {
                    MmkvManager.removeOrphanedServerProfiles()
                }
                if (removed == null) {
                    toastError(R.string.toast_profile_storage_cleanup_skipped)
                } else {
                    toastSuccess(getString(R.string.toast_profile_storage_cleanup, removed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clean up profile storage", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun shareBackup(cacheDir: File, appName: String, password: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName, password)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ShareFile(ret.second))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri, password: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName, password)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ExportLocal(ret.second, targetUri))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, backupFile: File, password: String? = null) {
        launchLoading {
            val success = performRestore(cacheDir, backupFile, password)
            if (success) {
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess(backupFile.absolutePath))
            } else {
                toastError(R.string.toast_failure)
                _viewModelEvent.send(BackupViewModelEvent.RestoreFailed(backupFile.absolutePath))
            }
        }
    }

    fun backupViaWebDav(cacheDir: File, appName: String, password: String) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache(cacheDir, appName, password)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                tempFile = File(ret.second)
                WebDavManager.init(config)

                val ok = try {
                    WebDavManager.uploadFile(tempFile, AppConfig.WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV upload error (${e.javaClass.simpleName})")
                    false
                }

                if (ok) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup error (${e.javaClass.simpleName})")
                toastError(R.string.toast_failure)
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun downloadWebDavBackup(cacheDir: File) {
        val config = _webDavConfig.value
        if (config == null || config.baseUrl.isEmpty()) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }
        launchLoading {
            var target = File(cacheDir, "download_${System.currentTimeMillis()}.aicpvpn")
            try {
                WebDavManager.init(config)
                var ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    target.delete()
                    target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                    ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_LEGACY_FILE_NAME, target)
                }
                if (ok) {
                    _viewModelEvent.send(BackupViewModelEvent.WebDavBackupDownloaded(target.absolutePath))
                } else {
                    target.delete()
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup download failed (${e.javaClass.simpleName})")
                target.delete()
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun backupConfigurationToCache(cacheDir: File, appName: String, password: String): Pair<Boolean, String> {
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(System.currentTimeMillis())
        val folderName = "${appName}_$dateFormatted"
        val stagingDir = File(cacheDir, "backup_staging").apply { if (!exists()) mkdirs() }
        val exportDir = File(cacheDir, "shared_backups").apply { if (!exists()) mkdirs() }
        if (!stagingDir.isDirectory || !exportDir.isDirectory) return Pair(false, "")
        val backupDir = File(stagingDir, folderName)
        val plainZip = File(exportDir, "$folderName.zip")
        val encryptedBackup = File(exportDir, "$folderName.aicpvpn")
        try {
            val count = MMKV.backupAllToDirectory(backupDir.absolutePath)
            if (count <= 0) return Pair(false, "")
            if (!ZipUtil.zipFromFolder(backupDir.absolutePath, plainZip.absolutePath)) return Pair(false, "")
            val passwordChars = password.toCharArray()
            try {
                BackupEncryption.encryptFile(plainZip, encryptedBackup, passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }
            return Pair(true, encryptedBackup.absolutePath)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Backup creation/encryption failed (${e.javaClass.simpleName})")
            encryptedBackup.delete()
            return Pair(false, "")
        } finally {
            backupDir.deleteRecursively()
            plainZip.delete()
        }
    }

    private suspend fun performRestore(cacheDir: File, backupFile: File, password: String?): Boolean =
        withContext(Dispatchers.IO) {
            val backupDir = File(cacheDir, "restore_${System.nanoTime()}")
            var decryptedZip: File? = null
            try {
                val zipFile = if (BackupEncryption.isEncrypted(backupFile)) {
                    if (password.isNullOrEmpty()) return@withContext false
                    decryptedZip = File(cacheDir, "restore-decrypted-${System.nanoTime()}.zip")
                    val passwordChars = password.toCharArray()
                    try {
                        BackupEncryption.decryptFile(backupFile, decryptedZip!!, passwordChars)
                    } finally {
                        passwordChars.fill('\u0000')
                    }
                    decryptedZip!!
                } else {
                    backupFile // Legacy plaintext ZIP; Activity requires an explicit warning/confirmation.
                }

                if (!ZipUtil.unzipToFolder(zipFile, backupDir.absolutePath)) return@withContext false
                val count = MMKV.restoreAllFromDirectory(backupDir.absolutePath)
                if (count <= 0) return@withContext false
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                true
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Backup restore failed (${e.javaClass.simpleName})")
                false
            } finally {
                backupDir.deleteRecursively()
                decryptedZip?.delete()
            }
        }
}
