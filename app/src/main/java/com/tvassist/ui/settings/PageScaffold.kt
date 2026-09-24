package com.tvassist.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.PremiumIconButton
import com.tvassist.ui.TxtPrimary

/** A standard sub-page: Back + title header, scrollable content. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 30.sp, color = TxtPrimary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))
        content()
    }
}
