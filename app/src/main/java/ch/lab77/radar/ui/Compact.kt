package ch.lab77.radar.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Boutons compacts pour toute l'app (retour Myrko n°9 : gros boutons sur petits écrans). */
private val small = TextStyle(fontSize = 12.sp)
private val pad = PaddingValues(horizontal = 10.dp, vertical = 0.dp)

@Composable
fun SmallButton(onClick: () -> Unit, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.buttonColors(), content: @Composable RowScope.() -> Unit) =
    Button(onClick = onClick, modifier = Modifier.height(32.dp), enabled = enabled, colors = colors, contentPadding = pad) {
        CompositionLocalProvider(LocalTextStyle provides small) { content() }
    }

@Composable
fun SmallOutlined(onClick: () -> Unit, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.height(32.dp), enabled = enabled, colors = colors, contentPadding = pad) {
        CompositionLocalProvider(LocalTextStyle provides small) { content() }
    }

@Composable
fun SmallText(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    TextButton(onClick = onClick, modifier = Modifier.height(30.dp), enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        CompositionLocalProvider(LocalTextStyle provides small) { content() }
    }
