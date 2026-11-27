package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus as Status

/** Main-thread observer. One terminal wake per pack transition; never wake on progress. */
internal class PlayQueueWakeGate {
    private val revisions = mutableMapOf<String, Long>()
    private val last = mutableMapOf<String, Int>()

    fun probe(): Map<String, Long> = revisions.toMap()

    fun live(pack: String, status: Int): Set<String> {
        revisions[pack] = (revisions[pack] ?: 0) + 1
        return if (accept(pack, status)) setOf(pack) else emptySet()
    }

    // Ignore results superseded by a newer live callback for the same pack.
    fun snapshot(probe: Map<String, Long>, states: Map<String, Int>): Set<String> =
        states.filter { (pack, status) -> revisions[pack] == probe[pack] && accept(pack, status) }.keys

    data class Reconciliation(
        val visible: Map<String, Int>,
        val accepted: Map<String, Int>,
        val terminal: Set<String>,
    )

    // Share the per-pack live revision across the UI, fetch selection and queue wake.
    // A partial initial probe may clear unconfirmed old rows, but not newer live rows.
    fun reconcile(
        probe: Map<String, Long>, before: Map<String, Int>, states: Map<String, Int>,
        replace: Boolean = false,
    ): Reconciliation {
        val accepted = states.filterKeys { revisions[it] == probe[it] }
        val retained = if (replace) before.filterKeys { revisions[it] != probe[it] } else before
        return Reconciliation(retained + accepted, accepted, snapshot(probe, accepted))
    }

    private fun accept(pack: String, status: Int): Boolean {
        if (!LibraryPackPolicy.pending(status) &&
            status != Status.COMPLETED && status != Status.FAILED && status != Status.CANCELED) return false
        return last.put(pack, status) != status &&
            (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELED)
    }
}
