# Health Connect → Claude

Connettore MCP personale che rende **i dati fitness e salute supportati da questo connettore e autorizzati in Android Health Connect** consultabili da Claude.

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

## Installazione

### 1. Scegli un segreto
Inventa una stringa casuale lunga (almeno 16 caratteri, meglio 32+, solo lettere e numeri), ad esempio generata dal tuo password manager. Protegge sia l'invio dei dati sia l'URL del connettore: **non condividerla**.

### 2. Pubblica il server su Cloudflare

Da un computer con Node.js e un account Cloudflare, esegui:

```bash
cd server
npm ci
npm run typecheck
npx wrangler login
npx wrangler deploy
npx wrangler secret put API_SECRET
```

Usa l’URL `workers.dev` indicato dal deploy.

### 3. Installa l'app Android
1. Con Android SDK e JDK 17, dalla cartella `android/` esegui `./gradlew :app:assembleDebug` (su Windows `gradlew.bat :app:assembleDebug`). L’APK è in `app/build/outputs/apk/debug/`.
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
- Il server conserva le copie sincronizzate nel Durable Object SQLite del tuo account Cloudflare. Quando usi il connettore, Claude riceve i dati restituiti dagli strumenti MCP richiesti.
- L'URL del connettore contiene il segreto: chi lo conosce può leggere i dati. Se pensi sia trapelato, cambia `API_SECRET` con `npx wrangler secret put API_SECRET`, poi aggiorna l'app e il connettore.
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
