package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/** Isolated ads placeholder; no ad SDK, tracking, or network requests are active. */
@Composable
fun HomeAdsSlot(modifier: Modifier = Modifier) {
    Surface(modifier.height(92.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF101A29), border = BorderStroke(1.dp, Color(0xFF344256))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.home_ads_label), color = Color(0xFF9BAEC3), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Spacer(Modifier.height(5.dp))
            Text(stringResource(R.string.home_ads_placeholder), color = Color(0xFF6F8298), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
