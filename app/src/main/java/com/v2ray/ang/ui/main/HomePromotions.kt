package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

private const val KASHKOOL_TOOLS_URL = "https://aicp-showcase.pages.dev/tools/"

@Composable
fun HomePromotions(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cyan = Color(0xFF22D3EE)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .background(Brush.verticalGradient(listOf(Color(0xFF080E18), Color(0xFF101C2D), Color(0xFF080E18))))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color(0xFF142B43), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0xFF28577C))) {
                Text("AICP  /  CONTROL HUB", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.home_welcome_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(stringResource(R.string.home_welcome_subtitle), color = Color(0xFFB7C7D9), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)
        }
        HomeAdsSlot(modifier = Modifier.fillMaxWidth())
        HomeServiceCard(stringResource(R.string.home_kashkool_eyebrow), stringResource(R.string.home_kashkool_title), stringResource(R.string.home_kashkool_description), "⌘", Color(0xFF3B82F6)) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KASHKOOL_TOOLS_URL))) }
                .onFailure { Toast.makeText(context, R.string.home_open_link_failed, Toast.LENGTH_SHORT).show() }
        }
        HomeServiceCard(stringResource(R.string.home_dub_eyebrow), stringResource(R.string.home_dub_title), stringResource(R.string.home_dub_description), "♫", cyan) {
            Toast.makeText(context, R.string.home_dub_link_not_configured, Toast.LENGTH_LONG).show()
        }
        Text(stringResource(R.string.home_footer_note), Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), color = Color(0xFF70849B), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeServiceCard(eyebrow: String, title: String, description: String, symbol: String, accent: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121F30)), border = BorderStroke(1.dp, accent.copy(alpha = 0.68f)), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(48.dp), shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.13f), border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(symbol, color = accent, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(eyebrow.uppercase(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(description, color = Color(0xFFB1C1D3), fontSize = 10.sp, lineHeight = 15.sp)
            }
            Text("↗", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}


/** Compact links kept visible on the default config screen as well as on the empty-state home page. */
@Composable
fun HomePromotionBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .background(Color(0xFF101C2D))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text("AICP website", color = Color(0xFF9BAEC3), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "aicp-showcase.pages.dev",
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aicp-showcase.pages.dev/"))) }
                        .onFailure { Toast.makeText(context, R.string.home_open_link_failed, Toast.LENGTH_SHORT).show() }
                },
                color = Color(0xFF22D3EE), fontSize = 11.sp, maxLines = 1
            )
        }
        Text(
            "Kashkool Tools ↗",
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KASHKOOL_TOOLS_URL))) }
                    .onFailure { Toast.makeText(context, R.string.home_open_link_failed, Toast.LENGTH_SHORT).show() }
            }.padding(horizontal = 8.dp, vertical = 8.dp),
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}
