package com.healthconnectclaude.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var urlInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val bg = HealthReader.PERMISSION_BACKGROUND in granted
            toast("Permessi concessi: ${granted.size}" + if (bg) "" else " (manca l'accesso in background)")
            refresh()
        }

        setContentView(buildUi())
        if (prefs.isConfigured) Syncer.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(text: String, size: Float = 14f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, pad / 2, 0, pad / 4)
        }.also { root.addView(it) }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }.also { root.addView(it) }

        label("Health Connect Claude", 22f)
        label("URL del server (es. https://health-connect-mcp.tuonome.workers.dev)")
        urlInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.serverUrl)
            hint = "https://…workers.dev"
        }.also { root.addView(it) }
        label("Segreto (API_SECRET impostato sul server)")
        secretInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.secret)
        }.also { root.addView(it) }

        button("1. Salva impostazioni") {
            prefs.serverUrl = urlInput.text.toString()
            prefs.secret = secretInput.text.toString()
            if (prefs.isConfigured) {
                Syncer.schedulePeriodic(this)
                toast("Salvato")
            } else {
                toast("L'URL deve iniziare con https:// e il segreto avere almeno 16 caratteri")
            }
            refresh()
        }
        button("2. Concedi permessi Health Connect") { requestPermissions() }
        button("3. Sincronizza ora") { runSync(null) }
        button("Sincronizza tutto lo storico (ultimo anno)") { runSync(365) }
        button("Copia URL per il connettore Claude") {
            if (!prefs.isConfigured) return@button toast("Prima salva le impostazioni")
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("MCP URL", prefs.connectorUrl))
            toast("Copiato: incollalo in Claude → Connettori → Aggiungi connettore personalizzato")
        }

        statusText = label("")
        logText = label("", 12f)

        val scroll = ScrollView(this).apply { addView(root) }
        // Android 15+ disegna l'app a tutto schermo: lascia spazio a barra di stato, notch e tastiera.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }

    private fun requestPermissions() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> permissionLauncher.launch(HealthReader.ALL_PERMISSIONS)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                toast("Aggiorna Health Connect")
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                )
            }
            else -> toast("Health Connect non è disponibile su questo dispositivo")
        }
    }

    private fun runSync(historyDays: Long?) {
        if (!prefs.isConfigured) return toast("Prima salva le impostazioni")
        logText.text = ""
        statusText.text = "Sincronizzazione in corso…"
        lifecycleScope.launch {
            val result = try {
                Syncer.sync(this@MainActivity, historyDays) { line -> runOnUiThread { logText.append(line + "\n") } }
            } catch (e: Exception) {
                "Errore: ${e.message}".also { prefs.lastStatus = it }
            }
            statusText.text = result
        }
    }

    private fun refresh() {
        if (!::statusText.isInitialized) return
        lifecycleScope.launch {
            val permInfo = if (HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE) {
                val granted = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                val bg = if (HealthReader.PERMISSION_BACKGROUND in granted) "sì" else "no"
                "Permessi: ${granted.size}/${HealthReader.ALL_PERMISSIONS.size} · background: $bg"
            } else {
                "Health Connect non disponibile"
            }
            statusText.text = listOf(permInfo, prefs.lastStatus.ifEmpty { "Mai sincronizzato" }).joinToString("\n")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/** Schermata informativa mostrata da Health Connect quando l'utente chiede come vengono usati i dati. */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            setPadding(pad, pad * 2, pad, pad)
            textSize = 16f
            text = "Questa app legge i tuoi dati da Health Connect in sola lettura e li invia " +
                "esclusivamente al server che hai configurato tu (il tuo Cloudflare Worker), " +
                "per renderli consultabili da Claude tramite un connettore MCP personale. " +
                "Nessun dato viene condiviso con terze parti."
        })
    }
}
