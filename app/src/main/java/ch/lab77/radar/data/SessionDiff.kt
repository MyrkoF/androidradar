package ch.lab77.radar.data

/**
 * Diff entre deux visites du même lieu (cahier §4) : nouveaux, disparus, déplacés (au-delà de la somme des
 * deux rayons), nom ou sécurité changés. Seules les positions confirmées comptent (stationnaire / RTT / △ / 🔒) :
 * les passants et les indéterminés ne font pas partie de la « carte réelle ». Pur Kotlin, testé.
 */
object SessionDiff {
    fun diff(before: Map<String, EstRow>, after: Map<String, EstRow>): List<DiffEntry> {
        val a = before.filterValues { it.confirmed }
        val b = after.filterValues { it.confirmed }
        val out = ArrayList<DiffEntry>()
        for ((id, e) in b) if (id !in a) out += DiffEntry(DiffKind.NEW, id, e.name, e.category, "${e.kind.name} · ±${e.radius.toInt()} m")
        for ((id, e) in a) if (id !in b) out += DiffEntry(DiffKind.GONE, id, e.name, e.category, "vu ${e.n}× avant · ±${e.radius.toInt()} m")
        for ((id, e2) in b) {
            val e1 = a[id] ?: continue
            val d = Estimator.distanceM(e1.lat, e1.lon, e2.lat, e2.lon)
            if (d > e1.radius + e2.radius) out += DiffEntry(DiffKind.MOVED, id, e2.name, e2.category, "${d.toInt()} m (au-delà de ${(e1.radius + e2.radius).toInt()} m d'incertitude)")
            val changes = ArrayList<String>()
            if (e1.name != e2.name) changes += "nom « ${e1.name} » → « ${e2.name} »"
            if (e1.security != e2.security) changes += "sécurité ${e1.security} → ${e2.security}"
            if (changes.isNotEmpty()) out += DiffEntry(DiffKind.CHANGED, id, e2.name, e2.category, changes.joinToString(" · "))
        }
        return out.sortedWith(compareBy<DiffEntry> { it.kind.ordinal }.thenByDescending { it.category.priority })
    }

    fun text(nameA: String, nameB: String, entries: List<DiffEntry>, countA: Int, countB: Int): String = buildString {
        appendLine("# Diff Radar — « $nameA » → « $nameB »")
        appendLine("Positions confirmées : $countA avant, $countB après. ${entries.size} différence(s).")
        appendLine()
        for (k in DiffKind.entries) {
            val list = entries.filter { it.kind == k }
            if (list.isEmpty()) continue
            appendLine("## ${k.label.replaceFirstChar { it.uppercase() }} (${list.size})")
            for (e in list) appendLine("- [${e.category.label}] ${e.id} « ${e.name.ifBlank { "—" }} » — ${e.detail}")
            appendLine()
        }
        if (entries.isEmpty()) appendLine("Aucune différence : le lieu est identique (sur ce que les deux visites ont confirmé).")
    }
}
