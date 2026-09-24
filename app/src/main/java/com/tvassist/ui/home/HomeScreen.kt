package com.tvassist.ui.home

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import com.tvassist.data.ha.ConnectionState
import com.tvassist.ui.cards.performPress
import com.tvassist.data.ha.Entity
import com.tvassist.ui.AccentButton
import com.tvassist.ui.AddButton
import com.tvassist.ui.CompactStatus
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.WebOnboarding
import com.tvassist.ui.cards.performPress
import com.tvassist.ui.entities.CategorizedEntityList

/** The Home destination: connection status + imported entities dashboard. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeContent(
    viewModel: ConnectionViewModel,
    entities: List<Entity>,
    connected: Boolean,
    settings: com.tvassist.data.settings.Settings,
    webOnboarding: WebOnboarding,
    connection: ConnectionState,
    onImport: () -> Unit,
    onCustomize: () -> Unit,
    onOpenCard: (String) -> Unit,
    /** Starts a spoken exchange with a conversation agent (the Talk press action). */
    onOpenAssist: (String) -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenMapCard: (String) -> Unit,
) {
    val overrides = settings.entityOverrides
    val actions = viewModel.controlActions
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        CompactStatus(connection)
        Spacer(Modifier.height(16.dp))
        HealthWarnings()
        if (!connected) {
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
        } else {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Entities (${entities.size})", fontSize = 22.sp, color = Color.White)
                Spacer(Modifier.width(20.dp))
                AddButton("Import entities", onImport)
                if (entities.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    AccentButton("Customize", onCustomize, leadingIcon = Icons.Rounded.Tune)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (entities.isEmpty()) {
                Text(
                    "No entities imported yet.\nTap \"Import entities\" to pick which Home Assistant " +
                        "entities the app should track — only those are loaded, keeping it fast.",
                    color = Color(0xFFBBBBBB),
                    fontSize = 16.sp,
                )
            } else {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Press to toggle · hold for more controls · Customize to edit names, icons & actions",
                    fontSize = 12.sp, color = TxtMuted,
                )
                Spacer(Modifier.height(10.dp))
                CategorizedEntityList(
                    entities = entities,
                    overrides = overrides,
                    repository = viewModel.repository,
                    onPrimary = { e ->
                        when {
                            e.isMapCard -> onOpenMapCard(e.entityId)
                            e.domain == "camera" -> onOpenCamera(e.entityId)
                            e.isPerson -> onOpenPerson(e.entityId)
                            else -> performPress(
                                overrides[e.entityId]?.singlePress ?: "default", e, actions,
                                { onOpenCard(it.entityId) }, single = true,
                                openVoice = { onOpenAssist(it.entityId) },
                            )
                        }
                    },
                    onMore = { e ->
                        when {
                            e.isMapCard -> onOpenMapCard(e.entityId)
                            e.domain == "camera" -> onOpenCamera(e.entityId)
                            e.isPerson -> onOpenPerson(e.entityId)
                            else -> performPress(
                                overrides[e.entityId]?.longPress ?: "default", e, actions,
                                { onOpenCard(it.entityId) }, single = false,
                                openVoice = { onOpenAssist(it.entityId) },
                            )
                        }
                    },
                )
            }
        }
    }
}
