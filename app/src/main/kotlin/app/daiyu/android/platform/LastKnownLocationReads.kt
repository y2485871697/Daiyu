package app.daiyu.android.platform

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject

/** Deliberately exposes only the cache API, not listeners, updates or current-location requests. */
internal fun interface LastKnownLocationSource {
    fun lastKnown(provider: String): Location?
}

internal class AndroidLastKnownLocationSource(private val context: Context) : LastKnownLocationSource {
    // Lazy: neither a missing permission nor missing destination consent touches LocationManager.
    private val manager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: error("unavailable")
    }
    @Suppress("MissingPermission") // Rechecked at the read boundary; OS SecurityException is handled there.
    override fun lastKnown(provider: String): Location? = manager.getLastKnownLocation(provider)
}

/** Effective admission, not a UI/process-state guess or a raw MODE_FOREGROUND check.
 * noteOpNoThrow resolves while-in-use eligibility at the OS boundary and also records the access.
 * Only an explicit MODE_ALLOWED is evidence that a null cache really means no cached location.
 */
internal object LastKnownLocationEligibility {
    enum class Result { ALLOWED, DENIED, UNKNOWN }

    @Suppress("DEPRECATION") // The three-argument overload is required on API 26..29.
    fun evaluate(context: Context, fine: Boolean): Result = try {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (manager == null) Result.UNKNOWN else {
            // Match the precision used for this read; never silently fall back after a fine denial.
            val op = if (fine) AppOpsManager.OPSTR_FINE_LOCATION else AppOpsManager.OPSTR_COARSE_LOCATION
            val mode = if (Build.VERSION.SDK_INT >= 30) {
                manager.noteOpNoThrow(op, Process.myUid(), context.packageName, context.attributionTag, null)
            } else {
                manager.noteOpNoThrow(op, Process.myUid(), context.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> Result.ALLOWED
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> Result.DENIED
                else -> Result.UNKNOWN // DEFAULT, unexpected modes: no effective admission evidence.
            }
        }
    } catch (_: SecurityException) {
        Result.DENIED
    } catch (e: android.os.OperationCanceledException) {
        throw e
    } catch (_: RuntimeException) {
        Result.UNKNOWN
    }
}

/** Bounded cache-only read. Provider order also supplies the stable tie-break; no provider enumeration.
 * Monotonic sample time determines newest/age, so wall-clock changes cannot make old records newest.
 * No altitude, speed, bearing, extras, addresses or identifiers leave this adapter.
 */
internal object LastKnownLocationReads {
    private data class Access(val coarse: Boolean, val fine: Boolean) {
        val granted get() = coarse || fine
    }
    private fun access(context: Context) = Access(hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))

    private fun denied() = failure("permission_denied", permissions = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))

    // This pre-consent check remains permission-only: do not note a private access before consent.
    fun permissionDenial(context: Context): JSONObject? = if (access(context).granted) null else denied()

    private fun admissionDenial(context: Context, initial: Access, cancelled: () -> Boolean): JSONObject? {
        checkReadCancellation(cancelled)
        if (access(context) != initial) return denied() // Includes fine -> coarse downgrade.
        val eligibility = LastKnownLocationEligibility.evaluate(context, initial.fine)
        checkReadCancellation(cancelled)
        if (access(context) != initial) return denied()
        return when (eligibility) {
            LastKnownLocationEligibility.Result.ALLOWED -> null
            LastKnownLocationEligibility.Result.DENIED -> denied()
            LastKnownLocationEligibility.Result.UNKNOWN -> failure("unavailable")
        }
    }

    fun read(context: Context, source: LastKnownLocationSource, cancelled: () -> Boolean,
        elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos): JSONObject {
        checkReadCancellation(cancelled)
        val initial = access(context)
        if (!initial.granted) return denied()
        admissionDenial(context, initial, cancelled)?.let { return it }
        val now = elapsedNanos()
        val providers = if (initial.fine) listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var newest: Location? = null
        var selectedProvider: String? = null
        var accessible = false
        var providerDenied = false
        for (provider in providers) {
            checkReadCancellation(cancelled)
            admissionDenial(context, initial, cancelled)?.let { return it }
            val record = try {
                source.lastKnown(provider).also { accessible = true }?.let { Location(it) }
            } catch (_: SecurityException) {
                providerDenied = true
                null // A provider may be inaccessible even when another cache is accessible.
            } catch (_: IllegalArgumentException) {
                null // Fixed provider absent on this device; do not enumerate or retry.
            }
            checkReadCancellation(cancelled)
            admissionDenial(context, initial, cancelled)?.let { return it }
            if (record == null || !valid(record, now)) continue
            if (newest == null || record.elapsedRealtimeNanos > newest.elapsedRealtimeNanos) {
                newest = record
                selectedProvider = provider
            }
        }
        checkReadCancellation(cancelled)
        admissionDenial(context, initial, cancelled)?.let { return it }
        val sample = newest ?: return when {
            accessible -> failure("no_cached_location")
            providerDenied -> denied()
            else -> failure("unavailable")
        }
        val data = JSONObject().put("scope", "last_known_only").put("source", "LocationManager.getLastKnownLocation")
            .put("provider", selectedProvider).put("sampled_at_ms", sample.time)
            .put("age_ms", (now - sample.elapsedRealtimeNanos) / 1_000_000L).put("age_clock", "elapsed_realtime")
            .put("permission_precision", if (initial.fine) "fine" else "coarse")
            .put("latitude", sample.latitude).put("longitude", sample.longitude)
        if (sample.hasAccuracy() && sample.accuracy.isFinite() && sample.accuracy >= 0)
            data.put("accuracy_m", sample.accuracy.toDouble())
        return success(data)
    }

    private fun valid(record: Location, now: Long): Boolean = record.time > 0 &&
        record.elapsedRealtimeNanos > 0 && record.elapsedRealtimeNanos <= now &&
        record.latitude.isFinite() && record.latitude in -90.0..90.0 &&
        record.longitude.isFinite() && record.longitude in -180.0..180.0
}
