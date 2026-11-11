import { handleMcp } from "./mcp";
import { HealthStore, type IncomingRecord } from "./store";

export { HealthStore };

interface Env {
  STORE: DurableObjectNamespace<HealthStore>;
  /** Segreto condiviso: usato dall'app Android (Bearer) e nell'URL del connettore MCP. */
  API_SECRET: string;
}

const MAX_RECORDS_PER_REQUEST = 5000;

/** Confronto a tempo costante per evitare timing attack sul segreto. */
function safeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}

function isValidRecord(r: unknown): r is IncomingRecord {
  if (!r || typeof r !== "object") return false;
  const o = r as Record<string, unknown>;
  return (
    typeof o.id === "string" &&
    typeof o.type === "string" &&
    typeof o.start === "string" &&
    typeof o.date === "string" &&
    /^\d{4}-\d{2}-\d{2}$/.test(o.date) &&
    (o.data === undefined || (typeof o.data === "object" && o.data !== null))
  );
}

async function handleIngest(request: Request, env: Env): Promise<Response> {
  if (request.method !== "POST") return new Response("Method Not Allowed", { status: 405 });
  const auth = request.headers.get("Authorization") ?? "";
  if (!safeEqual(auth, `Bearer ${env.API_SECRET}`)) return new Response("Unauthorized", { status: 401 });

  let body: { records?: unknown[]; deleted?: unknown[] };
  try {
    body = await request.json();
  } catch {
    return Response.json({ error: "JSON non valido" }, { status: 400 });
  }
  const records = Array.isArray(body.records) ? body.records : [];
  const deleted = Array.isArray(body.deleted) ? body.deleted.filter((d): d is string => typeof d === "string") : [];
  if (records.length > MAX_RECORDS_PER_REQUEST) {
    return Response.json({ error: `Massimo ${MAX_RECORDS_PER_REQUEST} record per richiesta` }, { status: 413 });
  }
  const valid = records.filter(isValidRecord);
  const result = await store(env).upsert(valid, deleted);
  return Response.json({ ok: true, ...result, rejected: records.length - valid.length });
}

function store(env: Env): DurableObjectStub<HealthStore> {
  return env.STORE.get(env.STORE.idFromName("main"));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!env.API_SECRET || env.API_SECRET.length < 16) {
      return new Response("API_SECRET non configurato (minimo 16 caratteri)", { status: 500 });
    }
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");

    if (path === "" || path === "/") {
      return new Response("Health Connect MCP server attivo.", { headers: { "content-type": "text/plain" } });
    }
    if (path === "/ingest") return handleIngest(request, env);

    // Endpoint MCP: /mcp/<API_SECRET>. Il segreto nell'URL fa da autenticazione per il connettore Claude.
    const m = path.match(/^\/mcp\/([^/]+)$/);
    if (m && safeEqual(decodeURIComponent(m[1]), env.API_SECRET)) {
      return handleMcp(request, store(env));
    }
    return new Response("Not Found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
