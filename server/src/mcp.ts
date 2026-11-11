import type { HealthStore, QueryOptions, StoredRecord } from "./store";

/**
 * Implementazione minimale e stateless del trasporto MCP "Streamable HTTP":
 * ogni POST contiene uno o più messaggi JSON-RPC e riceve la risposta in JSON.
 */

const SUPPORTED_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[0];

const SERVER_INSTRUCTIONS = `Questo server espone i dati di salute e fitness dell'utente, sincronizzati da Android Health Connect.
- Inizia con get_overview per sapere quali dati esistono e l'intervallo di date coperto.
- Per totali giornalieri (passi, distanza, calorie, sonno, frequenza cardiaca, minuti di esercizio) usa get_daily_summary: i valori sono già deduplicati tra le diverse app.
- Per allenamenti usa get_workouts, per il sonno get_sleep, per tutto il resto get_records.
- Le date sono giorni locali nel formato YYYY-MM-DD. Distanze in metri, energia in kcal, masse in kg.`;

type Json = Record<string, unknown>;

interface JsonRpcRequest {
  jsonrpc: "2.0";
  id?: string | number | null;
  method: string;
  params?: Json;
}

const dateProp = (desc: string) => ({ type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$", description: desc });

const RECORD_TYPES = [
  "DailySummary",
  "ExerciseSession",
  "SleepSession",
  "HeartRate",
  "RestingHeartRate",
  "HeartRateVariabilityRmssd",
  "Weight",
  "Height",
  "BodyFat",
  "LeanBodyMass",
  "BasalMetabolicRate",
  "OxygenSaturation",
  "RespiratoryRate",
  "BloodPressure",
  "BloodGlucose",
  "BodyTemperature",
  "Hydration",
  "Nutrition",
  "Vo2Max",
];

const TOOLS = [
  {
    name: "get_overview",
    title: "Panoramica dati",
    description:
      "Elenca i tipi di dati sanitari disponibili, con numero di record, prima e ultima data, e l'orario dell'ultima sincronizzazione dal telefono.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "get_daily_summary",
    title: "Riepilogo giornaliero",
    description:
      "Totali per giorno (deduplicati da Health Connect): passi, distanza (m), calorie attive e totali (kcal), piani saliti, dislivello (m), minuti di esercizio, minuti di sonno, frequenza cardiaca media/min/max, FC a riposo, peso medio, idratazione (L), calorie assunte.",
    inputSchema: {
      type: "object",
      properties: {
        start_date: dateProp("Primo giorno incluso (YYYY-MM-DD)"),
        end_date: dateProp("Ultimo giorno incluso (YYYY-MM-DD)"),
      },
      required: ["start_date", "end_date"],
      additionalProperties: false,
    },
  },
  {
    name: "get_workouts",
    title: "Allenamenti",
    description:
      "Sessioni di allenamento/esercizio con tipo di attività, durata, e (se disponibili) distanza, calorie, passi e frequenza cardiaca della sessione.",
    inputSchema: {
      type: "object",
      properties: {
        start_date: dateProp("Primo giorno incluso (YYYY-MM-DD)"),
        end_date: dateProp("Ultimo giorno incluso (YYYY-MM-DD)"),
        limit: { type: "integer", minimum: 1, maximum: 2000, description: "Numero massimo di risultati (default 200)" },
      },
      required: ["start_date", "end_date"],
      additionalProperties: false,
    },
  },
  {
    name: "get_sleep",
    title: "Sonno",
    description:
      "Sessioni di sonno con orario di inizio/fine, durata e minuti per fase (sveglio, leggero, profondo, REM). La data è quella del risveglio.",
    inputSchema: {
      type: "object",
      properties: {
        start_date: dateProp("Primo giorno incluso (YYYY-MM-DD)"),
        end_date: dateProp("Ultimo giorno incluso (YYYY-MM-DD)"),
        include_stages: { type: "boolean", description: "Includi l'elenco dettagliato delle fasi (default false)" },
      },
      required: ["start_date", "end_date"],
      additionalProperties: false,
    },
  },
  {
    name: "get_records",
    title: "Record per tipo",
    description:
      "Record grezzi di un tipo specifico in un intervallo di date. Tipi: " + RECORD_TYPES.join(", ") + ".",
    inputSchema: {
      type: "object",
      properties: {
        type: { type: "string", enum: RECORD_TYPES, description: "Tipo di record" },
        start_date: dateProp("Primo giorno incluso (YYYY-MM-DD)"),
        end_date: dateProp("Ultimo giorno incluso (YYYY-MM-DD)"),
        limit: { type: "integer", minimum: 1, maximum: 2000, description: "Numero massimo di risultati (default 500)" },
        newest_first: { type: "boolean", description: "Ordina dal più recente (default false)" },
      },
      required: ["type", "start_date", "end_date"],
      additionalProperties: false,
    },
  },
].map((t) => ({ ...t, annotations: { readOnlyHint: true, openWorldHint: false } }));

class ToolError extends Error {}

function str(args: Json, key: string, required = true): string | undefined {
  const v = args[key];
  if (v === undefined || v === null || v === "") {
    if (required) throw new ToolError(`Parametro mancante: ${key}`);
    return undefined;
  }
  if (typeof v !== "string") throw new ToolError(`Il parametro ${key} deve essere una stringa`);
  return v;
}

function dateArg(args: Json, key: string): string {
  const v = str(args, key)!;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) throw new ToolError(`${key} deve essere nel formato YYYY-MM-DD`);
  return v;
}

function num(args: Json, key: string): number | undefined {
  const v = args[key];
  if (v === undefined || v === null) return undefined;
  const n = Number(v);
  if (!Number.isFinite(n)) throw new ToolError(`Il parametro ${key} deve essere un numero`);
  return n;
}

function compact(r: StoredRecord, extra: Json = {}): Json {
  return { date: r.date, start: r.start, end: r.end ?? undefined, source: r.source ?? undefined, ...r.data, ...extra };
}

async function query(store: DurableObjectStub<HealthStore>, opts: QueryOptions): Promise<StoredRecord[]> {
  return JSON.parse(await store.query(opts)) as StoredRecord[];
}

async function callTool(store: DurableObjectStub<HealthStore>, name: string, args: Json): Promise<unknown> {
  switch (name) {
    case "get_overview":
      return store.overview();

    case "get_daily_summary": {
      const from = dateArg(args, "start_date");
      const to = dateArg(args, "end_date");
      const rows = await query(store, { type: "DailySummary", from, to, limit: 2000 });
      return { days: rows.map((r) => ({ date: r.date, ...r.data })) };
    }

    case "get_workouts": {
      const from = dateArg(args, "start_date");
      const to = dateArg(args, "end_date");
      const rows = await query(store, { type: "ExerciseSession", from, to, limit: num(args, "limit") ?? 200 });
      return { workouts: rows.map((r) => compact(r)) };
    }

    case "get_sleep": {
      const from = dateArg(args, "start_date");
      const to = dateArg(args, "end_date");
      const includeStages = args["include_stages"] === true;
      const rows = await query(store, { type: "SleepSession", from, to, limit: 2000 });
      return {
        sleep: rows.map((r) => {
          const out = compact(r);
          if (!includeStages) delete out["stages"];
          return out;
        }),
      };
    }

    case "get_records": {
      const type = str(args, "type")!;
      const from = dateArg(args, "start_date");
      const to = dateArg(args, "end_date");
      const rows = await query(store, {
        type,
        from,
        to,
        limit: num(args, "limit") ?? 500,
        order: args["newest_first"] === true ? "desc" : "asc",
      });
      return { type, count: rows.length, records: rows.map((r) => compact(r)) };
    }

    default:
      throw new ToolError(`Strumento sconosciuto: ${name}`);
  }
}

async function handleMessage(
  msg: JsonRpcRequest,
  store: DurableObjectStub<HealthStore>,
): Promise<Json | null> {
  const isNotification = msg.id === undefined;
  const reply = (result: unknown): Json => ({ jsonrpc: "2.0", id: msg.id, result });
  const fail = (code: number, message: string): Json => ({ jsonrpc: "2.0", id: msg.id ?? null, error: { code, message } });

  if (msg?.jsonrpc !== "2.0" || typeof msg.method !== "string") {
    return fail(-32600, "Invalid Request");
  }
  if (isNotification) return null; // notifications/initialized, notifications/cancelled, ...

  switch (msg.method) {
    case "initialize": {
      const requested = msg.params?.["protocolVersion"];
      const protocolVersion =
        typeof requested === "string" && SUPPORTED_PROTOCOL_VERSIONS.includes(requested)
          ? requested
          : LATEST_PROTOCOL_VERSION;
      return reply({
        protocolVersion,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "health-connect-mcp", title: "Health Connect", version: "1.0.0" },
        instructions: SERVER_INSTRUCTIONS,
      });
    }
    case "ping":
      return reply({});
    case "tools/list":
      return reply({ tools: TOOLS });
    case "tools/call": {
      const name = msg.params?.["name"];
      const args = (msg.params?.["arguments"] ?? {}) as Json;
      if (typeof name !== "string") return fail(-32602, "Missing tool name");
      if (!TOOLS.some((t) => t.name === name)) return fail(-32602, `Unknown tool: ${name}`);
      try {
        const result = await callTool(store, name, args);
        return reply({ content: [{ type: "text", text: JSON.stringify(result) }], isError: false });
      } catch (e) {
        const message = e instanceof Error ? e.message : String(e);
        return reply({ content: [{ type: "text", text: `Errore: ${message}` }], isError: true });
      }
    }
    case "resources/list":
      return reply({ resources: [] });
    case "prompts/list":
      return reply({ prompts: [] });
    default:
      return fail(-32601, `Method not found: ${msg.method}`);
  }
}

export async function handleMcp(request: Request, store: DurableObjectStub<HealthStore>): Promise<Response> {
  if (request.method !== "POST") {
    // Nessuno stream SSE lato server: il client userà solo POST.
    return new Response("Method Not Allowed", { status: 405, headers: { Allow: "POST" } });
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return Response.json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, { status: 400 });
  }

  const messages = Array.isArray(body) ? body : [body];
  const responses = (await Promise.all(messages.map((m) => handleMessage(m as JsonRpcRequest, store)))).filter(
    (r): r is Json => r !== null,
  );

  if (responses.length === 0) return new Response(null, { status: 202 });
  return Response.json(Array.isArray(body) ? responses : responses[0]);
}
