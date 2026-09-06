package ch.lab77.radar.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Puce compacte pour les petits écrans (retour Myrko : les gros boutons se compressaient et s'empilaient). */
@Composable
fun CompactChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean = true, selectedColor: Color? = null, leadingIcon: (@Composable () -> Unit)? = null) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, fontSize = 12.sp) }, leadingIcon = leadingIcon,
        modifier = Modifier.height(30.dp),
        colors = if (selectedColor != null) FilterChipDefaults.filterChipColors(selectedContainerColor = selectedColor, selectedLabelColor = Palette.bg, selectedLeadingIconColor = Palette.bg) else FilterChipDefaults.filterChipColors(),
    )
}
