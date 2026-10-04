package org.resqmesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import org.resqmesh.app.core.LocationContext

/** One foreground, user-requested lookup. No background tracking or network dependency. */
class SosLocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cancellations = mutableListOf<CancellationSignal>()
    private val listeners = mutableListOf<LocationListener>()
    private var timeout: Runnable? = null
    private var generation = 0

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(): LocationContext? {
        if (!hasPermission()) return null
        return runCatching {
            manager.allProviders.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .mapNotNull(::metadata).maxByOrNull { it.observedAt ?: 0 }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun request(completed: (LocationContext?) -> Unit) {
        cancel()
        if (!hasPermission()) { completed(null); return }
        val token = generation
        val providers = runCatching { manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER } }.getOrDefault(emptyList())
        if (providers.isEmpty()) { completed(lastKnown()); return }
        fun finish(location: LocationContext?) {
            if (token != generation) return
            cancel(); completed(location)
        }
        timeout = Runnable { finish(lastKnown()) }.also { handler.postDelayed(it, 12_000) }
        var pending = providers.size
        providers.forEach { provider ->
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal(); cancellations.add(signal)
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        val result = location?.let(::metadata)
                        if (result != null) finish(result)
                        else if (--pending == 0) finish(lastKnown())
                    }
                } else {
                    val listener = object: LocationListener {
                        override fun onLocationChanged(location: Location) { metadata(location)?.let { finish(it) } }
                        @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    listeners.add(listener)
                    @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (_: SecurityException) { if (--pending == 0) finish(lastKnown()) }
              catch (_: IllegalArgumentException) { if (--pending == 0) finish(lastKnown()) }
        }
    }

    fun cancel() {
        generation++
        timeout?.let(handler::removeCallbacks); timeout = null
        cancellations.forEach { it.cancel() }; cancellations.clear()
        listeners.forEach { runCatching { manager.removeUpdates(it) } }; listeners.clear()
    }

    private fun metadata(location: Location): LocationContext? {
        if (location.time <= 0 || location.time > System.currentTimeMillis() + 60_000 ||
            !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
        return LocationContext("device", location.time, location.latitude, location.longitude,
            location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0 }?.toDouble())
    }
}
