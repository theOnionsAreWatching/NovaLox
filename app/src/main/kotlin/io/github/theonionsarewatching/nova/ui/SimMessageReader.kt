package io.github.theonionsarewatching.nova.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.Locale

/** Reads and deletes SMS stored on a SIM via the stock ICC provider.
 * Never touches the app database
 * or the phone SMS inbox. */
class SimMessageReader(private val context: Context) {
    fun gate(): SimFailure? {
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) {
            return SimFailure.ROLE
        }
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != 0) {
            return SimFailure.PHONE_PERMISSION
        }
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != 0) {
            return SimFailure.SMS_PERMISSION
        }
        return null
    }

    fun active(): List<SimTarget> {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val info = manager.activeSubscriptionInfoList ?: emptyList()
        return info.map { subscription ->
            val cardId = runCatching { subscription.cardId }.getOrElse { -1 }
            SimTarget(subscription.subscriptionId, subscription.simSlotIndex, cardId)
        }.filter { SimReadPolicy.valid(it) }.sortedBy { it.slot }
    }

    fun uri(target: SimTarget): Uri =
        Uri.parse("content://sms/icc_subId/${target.subscriptionId}")

    private fun query(target: SimTarget, route: SimRoute): SimReadResult {
        fun Cursor.field(name: String): String {
            val index = getColumnIndex(name)
            return if (index < 0 || isNull(index)) "" else getString(index) ?: ""
        }
        val result = try {
            val uri = if (route == SimRoute.SUBSCRIPTION) {
                uri(target)
            } else {
                Uri.parse("content://sms/icc").buildUpon()
                    .appendQueryParameter("subscription", target.subscriptionId.toString())
                    .build()
            }
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor == null) {
                SimReadResult.Failure(SimFailure.NO_LIST)
            } else {
                cursor.use { rows ->
                    val bodyCol = rows.getColumnIndex("body")
                    val indexCol = rows.getColumnIndex("index_on_icc")
                    val subCol = rows.getColumnIndex("sub_id")
                    if (bodyCol < 0) {
                        SimReadResult.Failure(SimFailure.SCHEMA)
                    } else {
                        val collected = ArrayList<SimRecord>()
                        var stale = false
                        while (rows.moveToNext()) {
                            if (subCol >= 0) {
                                if (rows.isNull(subCol) ||
                                    !SimReadPolicy.ownedBy(rows.getString(subCol), target)
                                ) {
                                    stale = true
                                }
                            }
                            val index = if (indexCol >= 0 && !rows.isNull(indexCol)) {
                                SimReadPolicy.recordIndex(rows.getString(indexCol))
                            } else {
                                null
                            }
                            collected.add(
                                SimRecord(
                                    target,
                                    index,
                                    rows.field("address"),
                                    rows.getString(bodyCol) ?: "",
                                    rows.field("date")
                                )
                            )
                        }
                        when {
                            stale -> SimReadResult.Failure(SimFailure.STALE)
                            route == SimRoute.DEFAULT && collected.isNotEmpty() && subCol < 0 ->
                                SimReadResult.Failure(SimFailure.SCHEMA)
                            else -> SimReadResult.Records(collected.toList())
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            SimReadResult.Failure(SimFailure.DENIED)
        } catch (_: UnsupportedOperationException) {
            SimReadResult.Failure(SimFailure.UNSUPPORTED)
        } catch (e: IllegalArgumentException) {
            val lower = (e.message ?: "").lowercase(Locale.ROOT)
            val unsupported = listOf("unknown uri", "unsupported uri", "invalid uri")
            val code = if (unsupported.any { it in lower }) SimFailure.UNSUPPORTED else SimFailure.PROVIDER
            SimReadResult.Failure(code)
        } catch (_: Exception) {
            SimReadResult.Failure(SimFailure.PROVIDER)
        }
        val outcome = when (result) {
            is SimReadResult.Failure -> result.code.name
            is SimReadResult.Records -> "ROWS:${result.rows.size}"
        }
        Log.i(
            "DsmsSim",
            "read route=$route sub=${target.subscriptionId} slot=${target.slot} outcome=$outcome"
        )
        return result
    }

    fun read(target: SimTarget): SimReadResult = try {
        val blocked = gate()
        if (blocked != null) {
            SimReadResult.Failure(blocked)
        } else {
            SimReadPolicy.read(
                target,
                { active() },
                {
                    SimReadPolicy.agreedDefault(
                        SubscriptionManager.getDefaultSmsSubscriptionId(),
                        SubscriptionManager.getDefaultSubscriptionId()
                    )
                },
                { query(target, it) },
                { Thread.sleep(350L) }
            )
        }
    } catch (_: SecurityException) {
        SimReadResult.Failure(SimFailure.DENIED)
    } catch (_: Exception) {
        SimReadResult.Failure(SimFailure.PROVIDER)
    }

    fun delete(row: SimRecord, selected: SimTarget?): String = try {
        if (gate() != null) {
            "SIM access changed. Reload first."
        } else if (!SimReadPolicy.canDelete(row, selected, active(), read(row.target))) {
            "SIM or record changed, or identity unavailable. Reload; nothing deleted."
        } else if (gate() == null && SimReadPolicy.matches(row.target, active())) {
            val deleted = context.contentResolver.delete(
                uri(row.target).buildUpon().appendPath(row.index.toString()).build(),
                null,
                null
            )
            if (deleted <= 0) {
                "SIM deletion was not accepted. Reload to check."
            } else if (SimReadPolicy.deletionVerified(row, read(row.target))) {
                "Deleted from SIM."
            } else {
                "Delete requested; result not verified. Reload to check."
            }
        } else {
            "SIM access or selection changed. Nothing deleted."
        }
    } catch (_: Exception) {
        "SIM deletion failed or is unverified. Reload to check."
    }
}
