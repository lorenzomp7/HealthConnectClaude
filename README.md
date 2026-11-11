# Health Connect → Claude

Connettore MCP personale che rende **tutti i tuoi dati fitness e salute di Android Health Connect** consultabili da Claude.

```
┌──────────────┐  ogni ora   ┌───────────────────────────┐   MCP (HTTPS)   ┌────────┐
│ App Android  │ ──────────▶ │ Server MCP                │ ◀────────────── │ Claude │
│ (Health      │   /ingest   │ Cloudflare Worker +       │ /mcp/<segreto>  │        │
│  Connect)    │             │ Durable Object (SQLite)   │                 │        │
└──────────────┘             └───────────────────────────┘                 └────────┘
```

Health Connect non ha un'API cloud: i dati stanno solo sul telefono. Per questo servono due pezzi:

- **`server/`**: un server MCP su Cloudflare Workers (piano gratuito) che conserva i dati e li espone a Claude.
- **`android/`**: una piccola app che legge Health Connect e invia i dati al server ogni ora.

## Dati sincronizzati

| Strumento MCP | Contenuto |
|---|---|
| `get_overview` | Tipi di dati disponibili, intervallo di date, ultima sincronizzazione |
| `get_daily_summary` | Totali per giorno (deduplicati tra le app): passi, distanza, calorie attive/totali, piani, dislivello, minuti di esercizio, sonno, FC media/min/max, FC a riposo, peso, idratazione, calorie assunte |
| `get_workouts` | Allenamenti: tipo, durata, distanza, calorie, passi, FC media/max |
| `get_sleep` | Sonno: orari, durata, minuti per fase (leggero/profondo/REM/sveglio) |
| `get_records` | Record grezzi: FC, FC a riposo, HRV, peso, altezza, massa grassa/magra, metabolismo basale, SpO₂, frequenza respiratoria, pressione, glicemia, temperatura, idratazione, alimentazione, VO₂ max |

## Installazione (si può fare tutto dal telefono)

### 1. Scegli un segreto
Inventa una stringa casuale lunga (almeno 16 caratteri, meglio 32+, solo lettere e numeri), ad esempio generata dal tuo password manager. Protegge sia l'invio dei dati sia l'URL del connettore: **non condividerla**.

### 2. Pubblica il server su Cloudflare
1. Crea un account gratuito su [dash.cloudflare.com](https://dash.cloudflare.com).
2. Copia il tuo **Account ID** (pagina *Workers & Pages*, colonna a destra).
3. Crea un **API token**: *My Profile → API Tokens → Create Token → modello "Edit Cloudflare Workers"*.
4. Su GitHub, in questo repository: *Settings → Secrets and variables → Actions → New repository secret* e crea:
   - `CLOUDFLARE_API_TOKEN` = il token
   - `CLOUDFLARE_ACCOUNT_ID` = l'Account ID
   - `API_SECRET` = il segreto del passo 1
5. *Actions → "Deploy server MCP (Cloudflare)" → Run workflow*.
6. Alla fine il log mostra l'URL del server, del tipo `https://health-connect-mcp.<tuo-sottodominio>.workers.dev`. Aprendolo nel browser deve comparire «Health Connect MCP server attivo.»

> In alternativa, da un computer: `cd server && npm install && npx wrangler deploy && npx wrangler secret put API_SECRET`.

### 3. Installa l'app Android
1. Su GitHub apri *Releases* e scarica l'ultimo `HealthConnectClaude.apk` (viene ricompilato automaticamente a ogni modifica di `android/`).
2. Installalo (consenti l'installazione da origini sconosciute se richiesto).
3. Nell'app:
   1. Inserisci l'URL del server e il segreto → **Salva impostazioni**.
   2. **Concedi permessi Health Connect**: abilita tutto, inclusi *accesso in background* e *accesso ai dati passati*.
   3. **Sincronizza ora** (la prima volta legge gli ultimi 30 giorni). Con **Sincronizza tutto lo storico** importi l'ultimo anno.
   4. **Copia URL per il connettore Claude**.

Da quel momento l'app sincronizza da sola circa ogni ora.

### 4. Aggiungi il connettore in Claude
*Impostazioni → Connettori → Aggiungi connettore personalizzato*:
- **Nome**: `Health Connect`
- **URL del server MCP**: quello copiato dall'app, cioè `https://health-connect-mcp.<tuo-sottodominio>.workers.dev/mcp/<API_SECRET>`

Poi chiedi ad esempio: *"Come è andato il mio sonno questa settimana rispetto alla precedente?"* oppure *"Riassumi i miei allenamenti di ottobre"*.

## Sicurezza e privacy
- I dati restano nel **tuo** account Cloudflare (Durable Object con SQLite), nessun servizio di terze parti.
- L'URL del connettore contiene il segreto: chi lo conosce può leggere i dati. Se pensi sia trapelato, cambia `API_SECRET` (aggiorna il secret su GitHub e rilancia il deploy), poi aggiorna l'app e il connettore.
- L'APK è firmato con una chiave di debug inclusa nel repository (`android/app/debug.keystore`), così ogni nuova build si installa come aggiornamento. Installa solo APK prodotti da questo repository.

## Note e limiti
- La sincronizzazione aggiunge/aggiorna i record; i record eliminati da Health Connect dopo essere stati inviati restano sul server.
- Senza il permesso *accesso in background* la sincronizzazione avviene solo con **Sincronizza ora**.
- Senza il permesso *accesso ai dati passati* Health Connect fornisce solo i 30 giorni precedenti alla prima concessione dei permessi.
- I totali giornalieri sono calcolati da Health Connect seguendo le priorità tra le app impostate in *Health Connect → Dati e accesso*, quindi non contano due volte passi registrati da telefono e smartwatch.

## Sviluppo locale del server
```bash
cd server
npm install
echo 'API_SECRET=un-segreto-di-prova-lungo' > .dev.vars
npx wrangler dev
```
