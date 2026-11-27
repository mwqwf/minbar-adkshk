package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A cancellation request is not evidence that an in-flight installation stopped. */
internal object PlayPackRemoval {
    suspend fun unconfirmed(
        packs: List<String>,
        observe: suspend (String) -> Int?,
        installed: (String) -> Boolean?,
        remove: suspend (String) -> Boolean,
        settle: suspend () -> Unit,
        attempts: Int = 6,
    ): Set<String> = coroutineScope {
        packs.map { pack ->
            async { pack to confirmed(pack, observe, installed, remove, settle, attempts) }
        }.mapNotNull { result -> result.await().let { (pack, ok) -> pack.takeUnless { ok } } }.toSet()
    }

    private suspend fun confirmed(
        pack: String,
        observe: suspend (String) -> Int?,
        installed: (String) -> Boolean?,
        remove: suspend (String) -> Boolean,
        settle: suspend () -> Unit,
        attempts: Int,
    ): Boolean {
        val terminalWithoutInstall = setOf(AssetPackStatus.NOT_INSTALLED, AssetPackStatus.CANCELED, AssetPackStatus.FAILED)
        var absentLast = false
        var removalAttempted = false
        var removalAcknowledged = false
        repeat(attempts) { step ->
            val status = observe(pack)
            val present = installed(pack)
            // Play may finish installing after cancel was requested. Check both live
            // state and location; never remove the asset files ourselves.
            if (!removalAttempted && (status in terminalWithoutInstall || status == AssetPackStatus.COMPLETED) &&
                present == true) {
                removalAttempted = true
                removalAcknowledged = remove(pack)
            }
            val terminalAbsent = status in terminalWithoutInstall ||
                (removalAcknowledged && status == AssetPackStatus.COMPLETED)
            val absent = present == false && terminalAbsent
            if (absent && absentLast) return true
            absentLast = absent
            if (step + 1 < attempts) settle()
        }
        return false
    }
}
