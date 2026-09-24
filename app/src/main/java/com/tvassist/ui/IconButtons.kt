package com.tvassist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.graphics.vector.ImageVector

/** A compact icon-only button used for reorder/delete actions in the editor. */
@Composable
internal fun IconBtn(icon: ImageVector, desc: String, dense: Boolean = false, onClick: () -> Unit) =
    PremiumIconButton(icon = icon, desc = desc, onClick = onClick, dense = dense)

/** A button with a leading "+" icon and a label. */
@Composable
internal fun AddButton(label: String, onClick: () -> Unit) =
    AccentButton(label = label, onClick = onClick, leadingIcon = Icons.Rounded.Add)
