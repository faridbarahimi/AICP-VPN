package com.v2ray.ang.ui.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.ZipUtil
import com.v2ray.ang.util.BackupEncryption
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

enum class BackupLocation(@StringRes val labelRes: Int) {
    Local(R.string.backup_location_local),
    WebDav(R.string.backup_location_webdav)
}

class BackupActivity : HelperBaseComponentActivity() {

    private val viewModel: BackupViewModel by viewModels()
    private var pendingBackupAction by mutableStateOf<BackupAction?>(null)
    private var pendingRestoreFile by mutableStateOf<File?>(null)

    private enum class BackupAction { LOCAL, WEBDAV, SHARE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewModelEvent.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupViewModelEvent.ShareFile -> {
                            handleShareFile(event.filePath)
                        }

                        is BackupViewModel.BackupViewModelEvent.ExportLocal -> {
                            handleExportLocal(event.cachePath, event.targetUri)
                        }

                        is BackupViewModel.BackupViewModelEvent.WebDavBackupDownloaded -> {
                            pendingRestoreFile = File(event.filePath)
                        }

                        is BackupViewModel.BackupViewModelEvent.RestoreSuccess -> {
                            File(event.filePath).delete()
                            pendingRestoreFile = null
                            SettingsManager.initApp(this@BackupActivity)
                        }

                        is BackupViewModel.BackupViewModelEvent.RestoreFailed -> {
                            pendingRestoreFile = File(event.filePath)
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        BackupScreen(
            isLoadingState = viewModel.isLoading,
            webDavConfigState = viewModel.webDavConfig,
            onBackupOptionSelected = { location ->
                pendingBackupAction = when (location) {
                    BackupLocation.Local -> BackupAction.LOCAL
                    BackupLocation.WebDav -> BackupAction.WEBDAV
                }
            },
            onShareClick = { pendingBackupAction = BackupAction.SHARE },
            onRestoreOptionSelected = { location ->
                when (location) {
                    BackupLocation.Local -> restoreViaLocal()
                    BackupLocation.WebDav -> viewModel.downloadWebDavBackup(cacheDir)
                }
            },
            onCleanupProfiles = viewModel::cleanupProfileStorage,
            onWebDavSave = { config -> viewModel.saveWebDavConfig(config) },
            onBackClick = { finish() }
        )

        pendingBackupAction?.let { action ->
            BackupPasswordDialog(
                isRestore = false,
                onConfirm = { password ->
                    pendingBackupAction = null
                    when (action) {
                        BackupAction.LOCAL -> backupViaLocal(password)
                        BackupAction.WEBDAV -> viewModel.backupViaWebDav(cacheDir, getString(R.string.app_name), password)
                        BackupAction.SHARE -> viewModel.shareBackup(cacheDir, getString(R.string.app_name), password)
                    }
                },
                onDismiss = { pendingBackupAction = null },
            )
        }

        pendingRestoreFile?.let { file ->
            if (BackupEncryption.isEncrypted(file)) {
                BackupPasswordDialog(
                    isRestore = true,
                    onConfirm = { password ->
                        pendingRestoreFile = null
                        viewModel.restoreConfiguration(cacheDir, file, password)
                    },
                    onDismiss = {
                        file.delete()
                        pendingRestoreFile = null
                    },
                )
            } else {
                LegacyBackupWarningDialog(
                    onConfirm = {
                        pendingRestoreFile = null
                        viewModel.restoreConfiguration(cacheDir, file)
                    },
                    onDismiss = {
                        file.delete()
                        pendingRestoreFile = null
                    },
                )
            }
        }
    }

    private fun handleShareFile(filePath: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            BuildConfig.APPLICATION_ID + ".cache",
                            File(filePath)
                        )
                    ),
                getString(R.string.title_configuration_share)
            )
        )
    }

    private fun handleExportLocal(cachePath: String, targetUri: Uri) {
        try {
            val output = contentResolver.openOutputStream(targetUri)
                ?: throw IOException("Could not open the selected backup destination")
            output.use { destination ->
                File(cachePath).inputStream().use { input ->
                    HttpUtil.copyBounded(input, destination, BackupEncryption.MAX_ENCRYPTED_BYTES)
                }
            }
            if (!File(cachePath).delete()) {
                LogUtil.w(AppConfig.TAG, "Export succeeded but temporary backup cleanup was deferred")
            }
            toastSuccess(R.string.toast_success)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to copy backup to Uri (${e.javaClass.simpleName})")
            toastError(R.string.toast_failure)
        }
    }

    private fun backupViaLocal(password: String) {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val defaultFileName = "${getString(R.string.app_name)}_${dateFormatted}.aicpvpn"

        launchCreateDocument(defaultFileName) { uri ->
            if (uri != null) {
                viewModel.prepareBackupForUri(cacheDir, getString(R.string.app_name), uri, password)
            }
        }
    }

    private fun restoreViaLocal() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            val targetFile = File(cacheDir.absolutePath, "${System.currentTimeMillis()}.backup")
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open selected backup")
                input.use { source ->
                    targetFile.outputStream().use { fileOut ->
                        HttpUtil.copyBounded(source, fileOut, BackupEncryption.MAX_ENCRYPTED_BYTES)
                        fileOut.fd.sync()
                    }
                }
                pendingRestoreFile = targetFile
            } catch (e: Exception) {
                targetFile.delete()
                LogUtil.e(AppConfig.TAG, "Error during file restore (${e.javaClass.simpleName})")
                toastError(R.string.toast_failure)
            }
        }
    }
}

@Composable
private fun BackupPasswordDialog(
    isRestore: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val minimumLengthError = stringResource(R.string.backup_password_min_length, BackupEncryption.MIN_PASSWORD_LENGTH)
    val mismatchError = stringResource(R.string.backup_password_mismatch)
    val requiredError = stringResource(R.string.backup_password_required)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isRestore) R.string.backup_password_restore_title else R.string.backup_password_title)) },
        text = {
            Column {
                Text(stringResource(if (isRestore) R.string.backup_password_restore_description else R.string.backup_password_description))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (!isRestore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text(stringResource(R.string.backup_password_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.isEmpty() -> error = requiredError
                    !isRestore && password.length < BackupEncryption.MIN_PASSWORD_LENGTH -> error = minimumLengthError
                    !isRestore && password != confirmation -> error = mismatchError
                    else -> onConfirm(password)
                }
            }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun LegacyBackupWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_legacy_warning_title)) },
        text = { Text(stringResource(R.string.backup_legacy_warning_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_legacy_restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun BackupScreen(
    isLoadingState: StateFlow<Boolean>,
    webDavConfigState: StateFlow<WebDavConfig?>,
    onBackupOptionSelected: (BackupLocation) -> Unit,
    onShareClick: () -> Unit,
    onRestoreOptionSelected: (BackupLocation) -> Unit,
    onCleanupProfiles: () -> Unit,
    onWebDavSave: (WebDavConfig) -> Unit,
    onBackClick: () -> Unit
) {
    val isLoading by isLoadingState.collectAsState()
    val currentWebDavConfig by webDavConfigState.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }

    val webDavSummary = currentWebDavConfig?.baseUrl

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_configuration_backup_restore),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_backup_24dp),
                title = stringResource(R.string.title_configuration_backup),
                onClick = { showBackupDialog = true }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_share_24dp),
                title = stringResource(R.string.title_configuration_share),
                onClick = onShareClick
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_restore_24dp),
                title = stringResource(R.string.title_configuration_restore),
                onClick = { showRestoreDialog = true }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_delete_24dp),
                title = stringResource(R.string.title_profile_storage_cleanup),
                subtitle = stringResource(R.string.summary_profile_storage_cleanup),
                onClick = { showCleanupDialog = true }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_settings_24dp),
                title = stringResource(R.string.title_webdav_config_setting),
                subtitle = webDavSummary,
                onClick = { showWebDavDialog = true }
            )
            NavigationBarsSpacer()
        }
    }

    if (showBackupDialog) {
        SelectListDialog(
            title = stringResource(R.string.title_configuration_backup),
            options = BackupLocation.entries,
            optionText = { stringResource(it.labelRes) },
            onSelected = { location ->
                showBackupDialog = false
                onBackupOptionSelected(location)
            },
            onDismiss = { showBackupDialog = false }
        )
    }
    if (showRestoreDialog) {
        SelectListDialog(
            title = stringResource(R.string.title_configuration_restore),
            options = BackupLocation.entries,
            optionText = { stringResource(it.labelRes) },
            onSelected = { location ->
                showRestoreDialog = false
                onRestoreOptionSelected(location)
            },
            onDismiss = { showRestoreDialog = false }
        )
    }
    if (showCleanupDialog) {
        DeleteConfirmDialog(
            message = stringResource(R.string.message_profile_storage_cleanup),
            onConfirm = onCleanupProfiles,
            onDismiss = { showCleanupDialog = false }
        )
    }
    if (showWebDavDialog) {
        WebDavInputDialog(
            initialConfig = currentWebDavConfig,
            onSave = {
                showWebDavDialog = false
                onWebDavSave(it)
            },
            onDismiss = { showWebDavDialog = false }
        )
    }
}

@Composable
private fun WebDavInputDialog(
    initialConfig: WebDavConfig?,
    onSave: (WebDavConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(initialConfig?.baseUrl ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var remotePath by remember { mutableStateOf(initialConfig?.remoteBasePath ?: "/") }

    val fields = listOf(
        InputField(
            label = stringResource(R.string.title_webdav_url),
            value = url
        ),
        InputField(
            label = stringResource(R.string.title_webdav_user),
            value = username
        ),
        InputField(
            label = stringResource(R.string.title_webdav_pass),
            value = password,
            visualTransformation = PasswordVisualTransformation()
        ),
        InputField(
            label = stringResource(R.string.title_webdav_remote_path),
            value = remotePath
        )
    )

    InputDialog(
        title = stringResource(R.string.title_webdav_config_setting),
        fields = fields,
        onFieldChange = { index, value ->
            when (index) {
                0 -> url = value
                1 -> username = value
                2 -> password = value
                3 -> remotePath = value
            }
        },
        confirmText = stringResource(R.string.menu_item_save_config),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            onSave(
                WebDavConfig(
                    baseUrl = url.trim(),
                    username = username.trim().ifEmpty { null },
                    password = password,
                    remoteBasePath = remotePath.trim().ifEmpty { AppConfig.WEBDAV_BACKUP_DIR }
                )
            )
        },
        onDismiss = onDismiss
    )
}
