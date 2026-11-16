package io.github.theonionsarewatching.nova.ui

enum class SimFailure {
    NO_LIST, DENIED, UNSUPPORTED, SCHEMA, STALE, PROVIDER,
    PHONE_PERMISSION, SMS_PERMISSION, ROLE, NO_ACTIVE
}

enum class SimRoute { SUBSCRIPTION, DEFAULT }

data class SimTarget(val subscriptionId: Int, val slot: Int, val cardId: Int)

data class SimRecord(
    val target: SimTarget,
    val index: Int?,
    val address: String,
    val body: String,
    val date: String
)

sealed class SimReadResult {
    data class Records(val rows: List<SimRecord>) : SimReadResult()
    data class Failure(val code: SimFailure) : SimReadResult()
}

/** Pure SIM-read/delete rules. */
object SimReadPolicy {
    fun agreedDefault(sms: Int, overall: Int): Int =
        if (sms != overall || sms < 0 || sms == Int.MAX_VALUE) -1 else sms

    fun recordIndex(raw: String?): Int? =
        raw?.toIntOrNull()?.takeIf { it > 0 }

    fun ownedBy(raw: String?, target: SimTarget): Boolean {
        val id = raw?.toIntOrNull() ?: return false
        return id == target.subscriptionId
    }

    fun valid(target: SimTarget): Boolean =
        target.subscriptionId >= 0 &&
            target.subscriptionId != Int.MAX_VALUE &&
            target.slot >= 0

    fun matches(target: SimTarget, active: List<SimTarget>): Boolean {
        if (!valid(target)) return false
        if (active.count { it.subscriptionId == target.subscriptionId } != 1) return false
        if (active.count { it.slot == target.slot } != 1) return false
        return target in active
    }

    fun read(
        target: SimTarget,
        active: () -> List<SimTarget>,
        defaultId: () -> Int,
        query: (SimRoute) -> SimReadResult,
        pause: () -> Unit
    ): SimReadResult {
        fun checked(route: SimRoute): SimReadResult {
            if (!matches(target, active())) return SimReadResult.Failure(SimFailure.STALE)
            if (route == SimRoute.DEFAULT && defaultId() != target.subscriptionId) {
                return SimReadResult.Failure(SimFailure.STALE)
            }
            val result = query(route)
            if (!matches(target, active()) ||
                (route == SimRoute.DEFAULT && defaultId() != target.subscriptionId)
            ) {
                return SimReadResult.Failure(SimFailure.STALE)
            }
            if (result is SimReadResult.Records && result.rows.any { it.target != target }) {
                return SimReadResult.Failure(SimFailure.STALE)
            }
            return result
        }

        var route = SimRoute.SUBSCRIPTION
        var result = checked(route)
        if (result == SimReadResult.Failure(SimFailure.UNSUPPORTED) &&
            defaultId() == target.subscriptionId
        ) {
            route = SimRoute.DEFAULT
            result = checked(route)
        }
        if (result != SimReadResult.Failure(SimFailure.NO_LIST)) return result
        pause()
        return checked(route)
    }

    fun deletionVerified(row: SimRecord, after: SimReadResult): Boolean {
        val rows = (after as? SimReadResult.Records)?.rows ?: return false
        if (rows.any { it.target != row.target }) return false
        if (rows.any { it.index == row.index }) return false
        return true
    }

    fun canDelete(
        row: SimRecord,
        selected: SimTarget?,
        active: List<SimTarget>,
        result: SimReadResult
    ): Boolean {
        if (selected != row.target || row.target.cardId < 0 || !matches(row.target, active)) {
            return false
        }
        if ((row.index ?: 0) <= 0) return false
        val rows = (result as? SimReadResult.Records)?.rows ?: return false
        if (rows.any { it.target != row.target }) return false
        if (rows.count { it.target == row.target && it.index == row.index } != 1) return false
        return rows.count { it == row } == 1
    }
}
