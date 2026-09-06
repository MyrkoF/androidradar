package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lab77.radar.data.Category
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind

/**
 * Filtre d'affichage partagé par la Liste, le Radar et la Carte : types, catégories, disparus.
 * Ce qui est décoché disparaît des trois vues. Ne touche pas aux données ni aux exports.
 */
object ViewFilter {
    val hiddenCategories = mutableStateListOf<Category>()
    val hiddenKinds = mutableStateListOf<Kind>()
    var hideGone by mutableStateOf(false)      // masquer ce qui n'a plus été vu depuis 1 min

    fun accepts(d: Device) =
        d.category !in hiddenCategories && d.kind !in hiddenKinds && !(hideGone && d.ageMs > 60_000)

    fun toggle(c: Category) { if (!hiddenCategories.remove(c)) hiddenCategories.add(c) }
    fun toggle(k: Kind) { if (!hiddenKinds.remove(k)) hiddenKinds.add(k) }
    fun reset() { hiddenCategories.clear(); hiddenKinds.clear(); hideGone = false }
    val activeCount get() = hiddenCategories.size + hiddenKinds.size + (if (hideGone) 1 else 0)
}

private val shortLabel = mapOf(
    Category.CELLULAR_ROUTER to "Routeur cellulaire", Category.NETWORK_INFRA to "Infra réseau", Category.CAMERA to "Caméra",
    Category.INDUSTRIAL to "Industriel / IoT", Category.FLEET to "Flotte / télématique", Category.ROUTER_AP to "Routeur Wi-Fi / box",
    Category.CONSUMER to "Grand public", Category.CELL_TOWER to "Cellule mobile", Category.RANDOMIZED to "MAC aléatoire", Category.UNKNOWN to "Inconnu",
)

/** Bouton discret ⚙ qui ouvre le menu de filtres à cocher. À placer à droite de la barre de chaque vue. */
@Composable
fun FilterMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.FilterList, "Filtres", tint = if (ViewFilter.activeCount > 0) Palette.amber else Palette.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Afficher", color = Palette.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (k in Kind.entries) CheckRow(k.name, kindColor(k), k !in ViewFilter.hiddenKinds) { ViewFilter.toggle(k) }
            HorizontalDivider()
            for (c in Category.entries) CheckRow(shortLabel[c] ?: c.label, categoryColor(c), c !in ViewFilter.hiddenCategories) { ViewFilter.toggle(c) }
            HorizontalDivider()
            CheckRow("Masquer les disparus (> 1 min)", null, ViewFilter.hideGone) { ViewFilter.hideGone = !ViewFilter.hideGone }
            DropdownMenuItem(text = { Text("Tout réafficher") }, onClick = { ViewFilter.reset() })
        }
    }
}

@Composable
private fun CheckRow(label: String, dot: androidx.compose.ui.graphics.Color?, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) Box(Modifier.padding(end = 8.dp).size(8.dp).background(dot, CircleShape))
                Text(label)
            }
        },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        onClick = onToggle
    )
}
