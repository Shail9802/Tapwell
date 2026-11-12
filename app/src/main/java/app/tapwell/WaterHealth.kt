package app.tapwell

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class WaterExportStatus(val busy: Boolean = false, val permitted: Boolean = false, val message: String? = null)

internal fun WaterEvent.hydrationRecord(): HydrationRecord {
    val at = Instant.ofEpochMilli(time)
    val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
    return HydrationRecord(
        startTime = at.minusMillis(1), endTime = at,
        startZoneOffset = offset, endZoneOffset = offset,
        volume = Volume.milliliters(millilitres.toDouble()),
        metadata = Metadata.manualEntry(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = "tapwell-water-$id", clientRecordVersion = 1
        )
    )
}

internal class WaterHealth private constructor(private val context: Context) {
    private val store get() = Store.get(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(WaterExportStatus())
    val status = mutableStatus.asStateFlow()
    fun availability(): Int = HealthConnectClient.getSdkStatus(context)
    private fun client() = HealthConnectClient.getOrCreate(context)

    suspend fun refreshStatus() = mutex.withLock {
        if (availability() != HealthConnectClient.SDK_AVAILABLE) {
            mutableStatus.value = WaterExportStatus(message = "Install or update Health Connect to share water intake.")
            return@withLock
        }
        try {
            val permitted = client().permissionController.getGrantedPermissions().containsAll(permissions)
            if (!permitted && store.waterExportEnabled()) {
                store.disconnectWaterExport()
                cancelJob()
            }
            mutableStatus.value = WaterExportStatus(permitted = permitted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = WaterExportStatus(message = "Couldn’t check Health Connect. Try again.")
        }
    }

    suspend fun connect(granted: Set<String>) {
        mutex.withLock {
            if (!granted.containsAll(permissions)) {
                mutableStatus.value = WaterExportStatus(message = "Allow writing water intake to enable export. Nothing has been shared.")
                return
            }
            store.enableWaterExport()
            mutableStatus.value = WaterExportStatus(permitted = true)
        }
        flush()
    }

    suspend fun disconnect() = mutex.withLock {
        store.disconnectWaterExport()
        cancelJob()
        mutableStatus.value = WaterExportStatus(message = "Export stopped. Existing Health Connect entries are kept.")
    }

    /** Stable client IDs make retries safe even if the process dies after the remote insert. */
    suspend fun flush(): Boolean = mutex.withLock {
        if (!store.waterExportEnabled()) return@withLock true
        mutableStatus.value = WaterExportStatus(busy = true, permitted = true)
        try {
            withTimeout(30_000) {
                if (availability() != HealthConnectClient.SDK_AVAILABLE) {
                    mutableStatus.value = WaterExportStatus(message = "Health Connect is unavailable. Queued drinks stay on this phone.")
                    return@withTimeout
                }
                val health = client()
                if (!health.permissionController.getGrantedPermissions().containsAll(permissions)) throw SecurityException()
                while (store.waterExportEnabled()) {
                    val pending = store.waterEvents().filter { it.pending }.take(100)
                    if (pending.isEmpty()) break
                    health.insertRecords(pending.map { it.hydrationRecord() })
                    pending.forEach { store.markWaterExported(it.id) }
                }
                mutableStatus.value = WaterExportStatus(permitted = true)
            }
            store.waterEvents().none { it.pending }
        } catch (_: TimeoutCancellationException) {
            mutableStatus.value = WaterExportStatus(message = "Export timed out. Unsent drinks remain queued for retry.")
            false
        } catch (cancelled: CancellationException) {
            mutableStatus.value = WaterExportStatus(message = "Export paused. Unsent drinks remain queued.")
            throw cancelled
        } catch (_: SecurityException) {
            store.disconnectWaterExport()
            cancelJob()
            mutableStatus.value = WaterExportStatus(message = "Water permission was removed. Reconnect to export new drinks.")
            true
        } catch (_: Exception) {
            mutableStatus.value = WaterExportStatus(permitted = true, message = "Couldn’t export yet. Unsent drinks remain queued for retry.")
            false
        }
    }

    fun settingsIntent(): Intent = if (Build.VERSION.SDK_INT >= 34)
        Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
    else Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")

    fun installIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))

    @Suppress("DEPRECATION")
    fun samsungHydrationSupport(): String = try {
        val samsung = context.packageManager.getPackageInfo("com.sec.android.app.shealth", PackageManager.GET_PERMISSIONS)
        if ("android.permission.health.READ_HYDRATION" in samsung.requestedPermissions.orEmpty())
            "Samsung Health can request water access. Enable its Water read permission in Health Connect, then check Samsung Health."
        else "This installed Samsung Health version does not request water access through Health Connect. Tapwell exports won’t appear in its Water tracker. Direct Samsung water sync is not available in Tapwell yet."
    } catch (_: PackageManager.NameNotFoundException) {
        "Samsung Health isn’t installed. Other apps can use your water entries if you allow them access in Health Connect."
    }

    private fun cancelJob() = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

    companion object {
        private const val JOB_ID = 0x545703
        val permissions = setOf(HealthPermission.getWritePermission(HydrationRecord::class))
        private var instance: WaterHealth? = null
        fun get(context: Context): WaterHealth = instance ?: WaterHealth(context.applicationContext).also { instance = it }
        fun schedule(context: Context) {
            if (!Store.get(context).waterExportEnabled()) return
            get(context).let { exporter -> exporter.scope.launch { exporter.flush() } }
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, WaterExportJob::class.java))
                .setMinimumLatency(1_000).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }
}

class WaterExportJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch(start = CoroutineStart.LAZY) {
            val completed = WaterHealth.get(this@WaterExportJob).flush()
            jobFinished(params, !completed)
        }
        running!!.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
