package app.daiyu.android.platform

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
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

    fun permissionDenial(context: Context): JSONObject? = if (access(context).granted) null else denied()

    fun read(context: Context, source: LastKnownLocationSource, cancelled: () -> Boolean,
        elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos): JSONObject {
        checkReadCancellation(cancelled)
        val initial = access(context)
        if (!initial.granted) return denied()
        val now = elapsedNanos()
        val providers = if (initial.fine) listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var newest: Location? = null
        var selectedProvider: String? = null
        var accessible = false
        var providerDenied = false
        for (provider in providers) {
            checkReadCancellation(cancelled)
            if (access(context) != initial) return denied() // Includes fine -> coarse downgrade during a read.
            val record = try {
                source.lastKnown(provider).also { accessible = true }?.let { Location(it) }
            } catch (_: SecurityException) {
                providerDenied = true
                null // A provider may be inaccessible even when another cache is accessible.
            } catch (_: IllegalArgumentException) {
                null // Fixed provider absent on this device; do not enumerate or retry.
            }
            checkReadCancellation(cancelled)
            if (access(context) != initial) return denied()
            if (record == null || !valid(record, now)) continue
            if (newest == null || record.elapsedRealtimeNanos > newest.elapsedRealtimeNanos) {
                newest = record
                selectedProvider = provider
            }
        }
        checkReadCancellation(cancelled)
        if (access(context) != initial) return denied()
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
