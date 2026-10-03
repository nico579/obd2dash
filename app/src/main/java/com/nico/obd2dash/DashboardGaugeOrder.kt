package com.nico.obd2dash

/** Le même CSV conserve la sélection et son ordre, y compris pour les anciens réglages. */
internal object DashboardGaugeOrder {
    const val MAX_GAUGES = 6

    fun restore(saved: String?): List<Int> {
        if (saved == null) return PidCatalog.PRIMARY_PIDS.toList()
        val known = PidCatalog.defs.map { it.pid }.toSet()
        return saved.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in known }.distinct().take(MAX_GAUGES)
    }

    /** Les cadrans indisponibles gardent leur place lorsque seuls les visibles sont déplacés. */
    fun reorder(selected: List<Int>, supported: Set<Int>, visibleOrder: List<Int>): List<Int> {
        val visible = selected.filter { it in supported }
        // Refuser un geste devenu obsolète après une déconnexion ou un changement de sélection.
        if (visibleOrder.size != visible.size || visibleOrder.toSet() != visible.toSet()) return selected
        val reordered = visibleOrder.iterator()
        return selected.map { if (it in supported) reordered.next() else it }
    }
}
