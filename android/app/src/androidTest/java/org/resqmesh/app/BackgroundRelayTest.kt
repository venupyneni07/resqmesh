package org.resqmesh.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Service lifecycle only in simulation. Does not validate radio hardware, OEM kills, or microphone. */
@RunWith(AndroidJUnit4::class)
class BackgroundRelayTest {
    @Test fun userStartedRelaySurvivesActivityStopAndExplicitStopEndsIt() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val mesh = (context.applicationContext as MeshApplication).mesh
        await { mesh.state.value.ready }
        assumeFalse("Leave an existing user background session untouched", mesh.backgroundRelayEnabled())
        val originalSimulation = mesh.state.value.simulation
        val prefs = context.getSharedPreferences("resqmesh", 0)
        val hadPreference = prefs.contains("backgroundRelayEnabled")
        val originalEnabled = mesh.backgroundRelayEnabled()
        val originalRelay = mesh.state.value.relayEnabled
        val originalNodes = mesh.state.value.lab.nodes
        val originalEnvironment = prefs.getString("simulationEnvironment", null)
        val originalSelected = prefs.getString("selectedSim", null)
        val notificationsGranted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            if (!notificationsGranted) i.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
            mesh.setRelayEnabled(false); mesh.switchMode(true)
            originalNodes.forEach { mesh.simSetInternet(it.id, false) }
            barrier(mesh)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val started = CountDownLatch(1)
            scenario.onActivity { mesh.setBackgroundRelayEnabled(true) { error -> assertNull(error); started.countDown() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            await { mesh.backgroundRelayRunning() }
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 701 })
            scenario.moveToState(Lifecycle.State.CREATED)
            barrier(mesh)
            assertTrue("Visible persistent session owns the coordinator after Activity stop", mesh.backgroundRelayRunning())
            mesh.refresh(); barrier(mesh)
            val stopped = CountDownLatch(1)
            mesh.setBackgroundRelayEnabled(false) { assertNull(it); stopped.countDown() }
            assertTrue(stopped.await(5, TimeUnit.SECONDS)); await { !mesh.backgroundRelayRunning() }
            assertFalse(mesh.backgroundRelayEnabled())
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 701 })
        } finally {
            if (originalEnabled) {
                scenario?.moveToState(Lifecycle.State.RESUMED)
                scenario?.onActivity { mesh.setBackgroundRelayEnabled(true) {} }
            } else mesh.setBackgroundRelayEnabled(false) {}
            scenario?.close()
            mesh.setRelayEnabled(originalRelay); mesh.switchMode(originalSimulation)
            originalNodes.forEach { mesh.simSetInternet(it.id, it.internet) }
            // Persist restoration first even if a regression prevents the coordinator from draining.
            prefs.edit().putBoolean("simulation", originalSimulation).putBoolean("relayEnabled", originalRelay)
                .putString("simulationEnvironment", originalEnvironment).putString("selectedSim", originalSelected)
                .putBoolean("backgroundRelayEnabled", originalEnabled).commit()
            barrier(mesh)
            if (!hadPreference) prefs.edit().remove("backgroundRelayEnabled").commit()
            if (!notificationsGranted) i.uiAutomation.executeShellCommand("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
    }
    private fun barrier(mesh: MeshController) { val done = CountDownLatch(1); mesh.serial.execute { done.countDown() }; assertTrue(done.await(10, TimeUnit.SECONDS)) }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) { if (SystemClock.elapsedRealtime() > until) fail("Relay state did not settle"); SystemClock.sleep(50) }
    }
}
