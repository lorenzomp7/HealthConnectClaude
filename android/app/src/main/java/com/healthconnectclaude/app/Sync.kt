package com.healthconnectclaude.app

import android.content.Context
import android.content.SharedPreferences
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server_url", "") ?: ""
        set(v) = p.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var secret: String
        get() = p.getString("secret", "") ?: ""
        set(v) = p.edit().putString("secret", v.trim()).apply()

    /** Inizio dell'ultima sincronizzazione riuscita (epoch ms), 0 se mai. */
    var lastSyncMs: Long
        get() = p.getLong("last_sync_ms", 0L)
        set(v) = p.edit().putLong("last_sync_ms", v).apply()

    var lastStatus: String
        get() = p.getString("last_status", "") ?: ""
        set(v) = p.edit().putString("last_status", v).apply()

    val isConfigured get() = serverUrl.startsWith("https://") && secret.length >= 16
    val connectorUrl get() = "$serverUrl/mcp/$secret"
}

object Syncer {
    private val mutex = Mutex()
    private const val BATCH = 1000

    /**
     * Sincronizza i dati. Al primo avvio (o con [historyDays]) legge gli ultimi N giorni,
     * altrimenti riparte da 2 giorni prima dell'ultima sincronizzazione.
     */
    suspend fun sync(context: Context, historyDays: Long? = null, log: (String) -> Unit = {}): String =
        mutex.withLock {
            withContext(Dispatchers.IO) { doSync(context, historyDays, log) }
        }

    private suspend fun doSync(context: Context, historyDays: Long?, log: (String) -> Unit): String {
        val prefs = Prefs(context)
        if (!prefs.isConfigured) error("Configura prima URL del server e segreto")
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            error("Health Connect non disponibile su questo dispositivo")
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.none { it.startsWith("android.permission.health.READ_") }) {
            error("Nessun permesso Health Connect concesso")
        }

        val startedAt = Instant.now()
        val from = when {
            historyDays != null -> startedAt.minus(Duration.ofDays(historyDays))
            prefs.lastSyncMs > 0 -> Instant.ofEpochMilli(prefs.lastSyncMs).minus(Duration.ofDays(2))
            else -> startedAt.minus(Duration.ofDays(30))
        }
        log("Lettura dal ${DateTimeFormatter.ISO_LOCAL_DATE.format(from.atZone(ZoneId.systemDefault()))}…")

        val reader = HealthReader(client)
        val records = reader.readRawRecords(granted, from, startedAt, log) +
            reader.readDailySummaries(granted, from.atZone(ZoneId.systemDefault()).toLocalDate(), log)

        var sent = 0
        for (chunk in records.chunked(BATCH)) {
            upload(prefs, chunk)
            sent += chunk.size
            log("Inviati $sent/${records.size}")
        }
        prefs.lastSyncMs = startedAt.toEpochMilli()
        val status = "OK ${DateTimeFormatter.ofPattern("dd/MM HH:mm").format(startedAt.atZone(ZoneId.systemDefault()))}: ${records.size} record inviati"
        prefs.lastStatus = status
        return status
    }

    private fun upload(prefs: Prefs, records: List<JSONObject>) {
        val body = JSONObject().put("records", JSONArray(records)).toString().toByteArray(Charsets.UTF_8)
        val conn = URL("${prefs.serverUrl}/ingest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${prefs.secret}")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                error("Il server ha risposto $code ${err.take(200)}")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("health-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Sincronizzazione in background ogni ora (richiede il permesso "accesso in background"). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Syncer.sync(applicationContext)
        Result.success()
    } catch (e: SecurityException) {
        Prefs(applicationContext).lastStatus = "Background: permesso mancante (${e.message})"
        Result.failure()
    } catch (e: Exception) {
        Prefs(applicationContext).lastStatus = "Background: errore ${e.message}"
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
