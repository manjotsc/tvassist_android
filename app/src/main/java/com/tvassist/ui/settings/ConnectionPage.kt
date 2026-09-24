package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tvassist.ui.ConnectionStatusLine
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.home.OnboardingSection

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ConnectionPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val webOnboarding by viewModel.webOnboarding.collectAsStateWithLifecycle()
    PageScaffold("Connection", onBack) {
        ConnectionStatusLine(connection)
        Spacer(Modifier.height(4.dp))
        Text(
            if (settings.baseUrl.isNotBlank()) settings.baseUrl else "No Home Assistant configured",
            color = Color(0xFF999999), fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        OnboardingSection(
            initialUrl = settings.baseUrl,
            initialToken = settings.token,
            connection = connection,
            webOnboarding = webOnboarding,
            initialVerifySsl = settings.verifySsl,
            onConnect = { url, token, verify -> viewModel.saveAndConnect(url, token, verify) },
            onStartWeb = viewModel::startWebOnboarding,
            onStopWeb = viewModel::stopWebOnboarding,
        )
    }
}
