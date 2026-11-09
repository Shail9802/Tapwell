package app.tapwell

import android.accounts.Account
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.Scopes
import com.google.android.gms.tasks.Task
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SetupSync private constructor(private val context: Context) {
    private val store get() = Store.get(context)
    private val local get() = store.portableSetup
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus())
    val status = mutableStatus.asStateFlow()

    fun available() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
        com.google.android.gms.common.ConnectionResult.SUCCESS

    suspend fun authorize(): AuthorizationResult = requestAuthorization(local.sync()?.account)

    private suspend fun requestAuthorization(account: SyncAccount?): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .setOptOutIncludingGrantedScopes(true)
        account?.let { request.setAccount(Account(it.email, "com.google")) }
        return Identity.getAuthorizationClient(context).authorize(request.build()).awaitResult()
    }

    suspend fun connect(result: AuthorizationResult) {
        execute {
            val saved = local.sync()
            val token = result.accessToken ?: throw SignInNeeded()
            val drive = DriveSetup(token)
            val account = withContext(Dispatchers.IO) { drive.account() }
            if (saved != null && saved.account.id != account.id) throw SignInNeeded()
            synchronize(drive, account, saved)
        }
    }

    suspend fun refresh(): Boolean {
        if (local.sync() == null) return true
        return execute {
            val saved = local.sync() ?: return@execute
            var token = accessToken(saved.account)
            try {
                synchronize(DriveSetup(token), saved.account, saved)
            } catch (error: DriveSetupError) {
                if (error.status != 401) throw error
                val request = ClearTokenRequest.builder().setToken(token).build()
                Identity.getAuthorizationClient(context).clearToken(request).awaitResult()
                token = accessToken(saved.account)
                synchronize(DriveSetup(token), saved.account, local.sync())
            }
        }
    }

    private suspend fun accessToken(account: SyncAccount): String {
        val result = requestAuthorization(account)
        if (result.hasResolution()) throw SignInNeeded()
        return result.accessToken ?: throw SignInNeeded()
    }

    suspend fun disconnect() {
        execute {
            val account = local.sync()?.account ?: return@execute
            val request = RevokeAccessRequest.builder().setAccount(Account(account.email, "com.google"))
                .setScopes(scopes).build()
            Identity.getAuthorizationClient(context).revokeAccess(request).awaitResult()
            local.disconnect()
            val jobs = context.getSystemService(JobScheduler::class.java)
            jobs.cancel(EDIT_JOB)
            jobs.cancel(PERIODIC_JOB)
        }
    }

    fun authorizationFailed(error: Exception) {
        mutableStatus.value = SyncStatus(message = describe(error))
    }

    private suspend fun execute(action: suspend () -> Unit): Boolean = mutex.withLock {
        mutableStatus.value = SyncStatus(busy = true)
        try {
            action()
            mutableStatus.value = SyncStatus()
            true
        } catch (cancelled: CancellationException) {
            mutableStatus.value = SyncStatus()
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.value = SyncStatus(message = describe(error), reconnect = error is SignInNeeded)
            error !is IOException || (error is DriveSetupError && error.status in listOf(400, 401, 403))
        }
    }

    private suspend fun synchronize(drive: DriveSetup, account: SyncAccount, saved: SavedSync?) {
        val downloaded = withContext(Dispatchers.IO) {
            if (saved != null && drive.account().id != account.id) throw SignInNeeded()
            drive.download()
        }
        val current = local.capture()
        val device = local.deviceId()
        val merged = mergeSetup(current, saved?.baseline, saved?.outgoing.orEmpty(), downloaded.map { it.changes }, device)
        val oldHabits = store.habits()
        val staged = SavedSync(account, merged.setup, merged.outgoing, saved?.lastSync ?: 0)
        local.apply(merged.setup, staged)
        if (saved == null) schedulePeriodic(context)
        if (merged.setup != current) {
            val retained = store.habits().mapTo(mutableSetOf()) { it.id }
            oldHabits.filter { it.id !in retained }.forEach { Reminders.cancel(context, it) }
            Reminders.reconcile(context)
        }
        withContext(Dispatchers.IO) { drive.upload(device, downloaded, merged.outgoing) }
        local.markSynced(staged.copy(lastSync = System.currentTimeMillis()))
    }

    companion object {
        private val scopes = listOf(Scope(Scopes.DRIVE_APPFOLDER))
        private var instance: SetupSync? = null

        fun get(context: Context): SetupSync = instance ?: SetupSync(context.applicationContext).also { instance = it }

        fun scheduleEdit(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(EDIT_JOB) != null) return
            jobs.schedule(job(context, EDIT_JOB).setMinimumLatency(2_000).build())
        }

        private fun schedulePeriodic(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                job(context, PERIODIC_JOB).setPeriodic(15 * MINUTE).setPersisted(true).build()
            )
        }

        private fun job(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, SetupSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

        private fun describe(error: Exception) = when {
            error is SignInNeeded -> "Reconnect Google Drive to continue syncing."
            error is ApiException && error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
                "Google needs an Android OAuth client registered for this Tapwell build."
            error is DriveSetupError && error.status == 403 ->
                "Google denied access. Check that Drive API is enabled and that your account can use this OAuth app."
            error is DriveSetupError && error.status == 401 ->
                "Google Drive authorization expired. Reconnect and try again."
            error is IOException -> "Drive sync couldn’t finish. Your setup is still on this phone."
            error is ApiException -> "Google sign-in couldn’t finish (code ${error.statusCode})."
            else -> "Tapwell couldn’t read the backup. Check the app version and try again."
        }
    }
}

internal class SignInNeeded : Exception()

private const val EDIT_JOB = 0x545701
private const val PERIODIC_JOB = 0x545702

internal data class SyncStatus(val busy: Boolean = false, val message: String? = null, val reconnect: Boolean = false)

class SetupSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val running = scope.launch(start = CoroutineStart.LAZY) {
            val done = SetupSync.get(this@SetupSyncJob).refresh()
            jobs.remove(params.jobId)
            jobFinished(params, !done)
        }
        jobs[params.jobId] = running
        running.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { result ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> result.cancel()
            task.isSuccessful -> result.resume(task.result)
            else -> result.resumeWithException(task.exception ?: IllegalStateException("Google request failed"))
        }
    }
}
