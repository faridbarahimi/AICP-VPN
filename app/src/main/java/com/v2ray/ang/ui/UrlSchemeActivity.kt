package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = readIncomingPayload()
        lifecycleScope.launch {
            val payload = incoming?.first
            if (!payload.isNullOrBlank()) {
                if (!UrlSchemeImportPolicy.isAcceptable(payload)) {
                    toastError(R.string.toast_failure)
                } else {
                    val importPayload = UrlSchemeImportPolicy.appendFragment(payload, incoming.second)
                    try {
                        val (count, countSub) = withContext(Dispatchers.IO) {
                            AngConfigManager.importBatchConfig(importPayload, "", false)
                        }
                        if (count + countSub > 0) {
                            MessageHelper.sendMsg2UI(
                                applicationContext,
                                AppConfig.MSG_SERVERS_CHANGED,
                                "",
                            )
                            toast(R.string.import_subscription_success)
                        } else {
                            toast(R.string.import_subscription_failure)
                        }
                    } catch (error: Exception) {
                        // The incoming payload may contain subscription credentials; never log it or
                        // an exception message that could echo the URL.
                        LogUtil.e(AppConfig.TAG, "URL import failed (${error.javaClass.simpleName})")
                        toastError(R.string.toast_failure)
                    }
                }
            }
            startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun readIncomingPayload(): Pair<String, String?>? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { it to null }
                } else null
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                when (uri?.host) {
                    "install-config", "install-sub" -> uri.getQueryParameter("url")?.let { it to uri.fragment }
                    else -> {
                        toastError(R.string.toast_failure)
                        null
                    }
                }
            }
            else -> null
        }
    }

    @Composable
    override fun ScreenContent() {
    }


}


internal object UrlSchemeImportPolicy {
    const val MAX_PAYLOAD_CHARS = 1_048_576

    fun isAcceptable(payload: String?): Boolean =
        !payload.isNullOrBlank() && payload.length <= MAX_PAYLOAD_CHARS

    fun appendFragment(payload: String, fragment: String?): String {
        if (fragment.isNullOrEmpty()) return payload
        val current = runCatching { java.net.URI(payload).rawFragment }.getOrNull()
        return if (current.isNullOrEmpty()) "$payload#$fragment" else payload
    }
}
