package app.tapwell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun hasPreciseLocation(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal suspend fun locate(context: Context): Location? {
    if (!hasPreciseLocation(context)) return null
    val manager = context.getSystemService(LocationManager::class.java)
    if (!manager.isLocationEnabled) return null
    val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter(manager::isProviderEnabled)
    var best: Location? = null
    return withTimeoutOrNull(16_000) {
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            var outstanding = providers.size
            if (outstanding == 0) continuation.resume(null)
            for (provider in providers) {
                val accept: (Location?) -> Unit = { fix ->
                    outstanding--
                    if (continuation.isActive) {
                        val age = SystemClock.elapsedRealtimeNanos() - (fix?.elapsedRealtimeNanos ?: 0)
                        if (fix != null && !fix.isMock && fix.hasAccuracy() && age in 0..30_000_000_000L &&
                            (best?.accuracy ?: Float.MAX_VALUE) > fix.accuracy
                        ) best = fix
                        if ((best?.accuracy ?: Float.MAX_VALUE) <= 50 || outstanding == 0) {
                            continuation.resume(best)
                            cancel.cancel()
                        }
                    }
                }
                if (!continuation.isActive) break
                try {
                    manager.getCurrentLocation(provider, cancel, context.mainExecutor) { accept(it) }
                } catch (_: IllegalArgumentException) {
                    accept(null)
                } catch (_: SecurityException) {
                    accept(null)
                }
            }
        }
    } ?: best
}

internal fun atWork(settings: WorkSettings, fix: Location): Boolean {
    val latitude = settings.latitude ?: return false
    val longitude = settings.longitude ?: return false
    val distance = FloatArray(1)
    Location.distanceBetween(latitude, longitude, fix.latitude, fix.longitude, distance)
    return distance[0] + fix.accuracy <= settings.radiusMeters
}
