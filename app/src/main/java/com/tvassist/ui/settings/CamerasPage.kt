package com.tvassist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.text.font.FontWeight
import com.tvassist.ui.AccentButton
import com.tvassist.ui.ChipButton
import com.tvassist.ui.ConnectionViewModel
import com.tvassist.ui.TvTextField
import com.tvassist.ui.TxtMuted
import com.tvassist.ui.TxtPrimary

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CamerasPage(viewModel: ConnectionViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cameras = settings.localCameras
    // The camera currently being edited/added (null = the "add" form is collapsed).
    var draft by remember { mutableStateOf<com.tvassist.data.settings.LocalCamera?>(null) }

    PageScaffold("Cameras", onBack) {
        Text(
            "Add cameras by their direct stream URL so the TV plays them instantly — skipping Home " +
                "Assistant's HLS start-up delay. They appear as camera tiles you can place in the overlay.",
            color = TxtMuted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        WebSetupRow(viewModel, "Cameras")
        Spacer(Modifier.height(18.dp))

        // Video player — applies to camera tiles and notification/person-map streams.
        Text("Video player", fontSize = 14.sp, color = TxtMuted)
        Spacer(Modifier.height(2.dp))
        Text("Engine for camera/video streams. VLC handles more cameras (HEVC, quirky RTSP); ExoPlayer is lighter.", fontSize = 12.sp, color = TxtMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "exoplayer" to "ExoPlayer", "vlc" to "VLC").forEach { (value, label) ->
                ChipButton(label, settings.streamPlayer == value, onClick = { viewModel.setStreamPlayer(value) })
            }
        }
        Spacer(Modifier.height(16.dp))

        cameras.forEach { cam ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cam.name.ifBlank { "(unnamed)" }, color = TxtPrimary, fontSize = 15.sp, maxLines = 1)
                    Text(cam.streamUrl, color = TxtMuted, fontSize = 12.sp, maxLines = 1)
                }
                ChipButton("Edit", selected = draft?.id == cam.id, onClick = { draft = cam })
                Spacer(Modifier.width(8.dp))
                ChipButton("Delete", selected = false, onClick = { viewModel.deleteLocalCamera(cam.id); if (draft?.id == cam.id) draft = null })
            }
        }

        Spacer(Modifier.height(14.dp))
        val d = draft
        if (d == null) {
            AccentButton(
                "Add camera",
                {
                    draft = com.tvassist.data.settings.LocalCamera(
                        id = "cam_" + System.currentTimeMillis().toString(36),
                        name = "", streamUrl = "",
                    )
                },
                leadingIcon = Icons.Rounded.Add,
            )
        } else {
            Text(if (cameras.any { it.id == d.id }) "Edit camera" else "New camera", color = TxtPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Name", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.name, { draft = d.copy(name = it) }, "Front door")
            Spacer(Modifier.height(12.dp))
            Text("Stream URL", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.streamUrl, { draft = d.copy(streamUrl = it) }, "rtsp://user:pass@192.168.1.20:554/stream")
            Spacer(Modifier.height(12.dp))
            Text("Low-res stream URL (optional)", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.lowResUrl, { draft = d.copy(lowResUrl = it) }, "rtsp://user:pass@192.168.1.20:554/stream2")
            Text(
                "Played instead while another app (Netflix, a video app) is using the TV's video decoder — " +
                    "often the same address ending in /stream2. Without one, it shows the camera's snapshot instead.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Snapshot URL (optional)", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            TvTextField(d.snapshotUrl, { draft = d.copy(snapshotUrl = it) }, "http://192.168.1.20/snapshot.jpg")
            Spacer(Modifier.height(12.dp))
            Text("Player", color = TxtMuted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "exoplayer" to "ExoPlayer", "vlc" to "VLC").forEach { (v, label) ->
                    ChipButton(label, d.player == v, onClick = { draft = d.copy(player = v) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep refreshing", color = TxtPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                ChipButton(if (d.refresh) "On" else "Off", selected = d.refresh, onClick = { draft = d.copy(refresh = !d.refresh) })
            }
            Text(
                "For cameras that serve a short looped video instead of a continuous stream — " +
                    "reloads when the clip ends so it stays live.",
                color = TxtMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                AccentButton(
                    "Save",
                    {
                        if (d.name.isNotBlank() && d.streamUrl.isNotBlank()) {
                            viewModel.saveLocalCamera(d)
                            draft = null
                        }
                    },
                    leadingIcon = Icons.Rounded.Add,
                )
                Spacer(Modifier.width(10.dp))
                ChipButton("Cancel", selected = false, onClick = { draft = null })
            }
        }
    }
}
