package com.v2ray.ang.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.aicp.vpn.npv.Npv
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerAetherActivity
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AicpPublicConfigRefreshScheduler.schedule(this)
        mainViewModel.onAction(MainAction.Initialize)
        lifecycleScope.launch {
            mainViewModel.autoReconnectRequests.collect {
                requestServiceStart()
            }
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onStart() {
        super.onStart()
        // The service can have gone away while the screen was not shown, without a word if its process died.
        mainViewModel.onAction(MainAction.RefreshServiceState)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.onManualServiceStopRequested()
            LauncherManager.stopService(this)
            // A service whose process died takes no stop and answers nothing; asking for its state right
            // after lets that silence show the screen as stopped instead of leaving it connected.
            mainViewModel.onAction(MainAction.RefreshServiceState)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        mainViewModel.cancelPendingAutoReconnect()
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        mainViewModel.testSelectedServerRealPing()
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            EConfigType.AETHER.value -> Intent(this, ServerAetherActivity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser("*/*") { uri ->
            if (uri == null) return@launchFileChooser
            try {
                val displayName = queryDisplayName(uri).ifBlank { uri.lastPathSegment.orEmpty() }
                when (AicpProfileFileType.detect(displayName)) {
                    AicpProfileFileType.OPENVPN -> openExternalProfile(uri, "application/x-openvpn-profile", R.string.openvpn_external_import)
                    AicpProfileFileType.NPVT, AicpProfileFileType.NPVS -> importNpvFile(uri, displayName)
                    AicpProfileFileType.TEXT -> contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import selected profile file", e)
                toastError(R.string.profile_file_import_failed)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty()
            } else ""
        } finally {
            cursor?.close()
        }
    }

    /** Delegate formats whose runtime is not embedded in AICP to an installed native client. */
    private fun openExternalProfile(uri: Uri, mimeType: String, chooserTitleRes: Int) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (packageManager.queryIntentActivities(view, 0).isNotEmpty()) {
            startActivity(Intent.createChooser(view, getString(chooserTitleRes)))
            return
        }
        // Many OpenVPN clients and file handlers register only */* plus extension sniffing.
        val wildcard = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (packageManager.queryIntentActivities(wildcard, 0).isNotEmpty()) {
            startActivity(Intent.createChooser(wildcard, getString(chooserTitleRes)))
        } else {
            toastError(R.string.openvpn_client_required)
        }
    }

    /** Read and decrypt NPVT/NPVS locally; never upload or log configuration bytes or secrets. */
    private fun importNpvFile(uri: Uri, displayName: String) {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { readProfileBytesBounded(uri, MAX_NPV_FILE_BYTES) }
                val inspection = withContext(Dispatchers.Default) { JSONObject(Npv.inspect(displayName, data)) }
                if (!inspection.optBoolean("ok")) {
                    toastError(inspection.optString("error", getString(R.string.npv_decryption_failed)))
                    return@launch
                }
                if (inspection.optBoolean("needsPassword")) {
                    showNpvPasswordDialog(displayName, data)
                } else {
                    decryptAndImportNpv(displayName, data, "")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NPV profile import failed (${e.javaClass.simpleName})")
                toastError(R.string.npv_decryption_failed)
            }
        }
    }

    private fun showNpvPasswordDialog(displayName: String, data: ByteArray) {
        val passwordInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.npv_password_hint)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(128))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.npv_password_title)
            .setView(passwordInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordInput.text.toString()
                passwordInput.text?.clear()
                decryptAndImportNpv(displayName, data, password)
            }
            .show()
    }

    private fun decryptAndImportNpv(displayName: String, data: ByteArray, password: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { JSONObject(Npv.decrypt(displayName, data, password)) }
                if (!result.optBoolean("ok")) {
                    toastError(result.optString("error", getString(R.string.npv_decryption_failed)))
                    return@launch
                }
                val links = AicpNpvProfileExtractor.extractImportableLinks(result.optString("text"))
                if (links.isEmpty()) {
                    toastError(R.string.npv_no_importable_profiles)
                    return@launch
                }
                mainViewModel.onAction(MainAction.ImportBatchConfig(links.joinToString("\n")))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NPV decryption/import failed (${e.javaClass.simpleName})")
                toastError(R.string.npv_decryption_failed)
            }
        }
    }

    private fun readProfileBytesBounded(uri: Uri, maxBytes: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected file")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Selected profile exceeds the import size limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private companion object {
        const val MAX_NPV_FILE_BYTES = 4 * 1024 * 1024
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            EConfigType.AETHER -> ServerAetherActivity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
