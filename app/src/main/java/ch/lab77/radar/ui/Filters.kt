package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lab77.radar.data.Category
import ch.lab77.radar.data.Device

/** Filtre par catégorie, partagé entre la Liste et le Radar : une catégorie décochée disparaît des deux vues. */
object ViewFilter {
    val hidden = mutableStateListOf<Category>()
    fun accepts(d: Device) = d.category !in hidden
    fun toggle(c: Category) { if (!hidden.remove(c)) hidden.add(c) }
}

private val shortLabel = mapOf(
    Category.CELLULAR_ROUTER to "Cellulaire", Category.NETWORK_INFRA to "Infra", Category.CAMERA to "Caméra",
    Category.INDUSTRIAL to "Industriel", Category.FLEET to "Flotte", Category.CONSUMER to "Public",
    Category.RANDOMIZED to "Aléatoire", Category.UNKNOWN to "Inconnu",
)

@Composable
fun CategoryChips() {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Category.entries.forEach { c ->
            val on = c !in ViewFilter.hidden
            FilterChip(
                selected = on, onClick = { ViewFilter.toggle(c) },
                label = { Text(shortLabel[c] ?: c.label) },
                leadingIcon = { Box(Modifier.size(8.dp).background(if (on) categoryColor(c) else Palette.muted, CircleShape)) },
            )
        }
    }
}
