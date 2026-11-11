import { DurableObject } from "cloudflare:workers";

/** Un record proveniente da Health Connect, normalizzato dall'app Android. */
export interface IncomingRecord {
  id: string; // id univoco (metadata.id di Health Connect, o "daily:YYYY-MM-DD")
  type: string; // es. "ExerciseSession", "SleepSession", "Weight", "DailySummary"
  start: string; // ISO-8601
  end?: string | null; // ISO-8601 (solo per record a intervallo)
  date: string; // giorno locale YYYY-MM-DD (fuso orario del telefono)
  source?: string | null; // pacchetto dell'app di origine
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: Record<string, any>;
}

export interface QueryOptions {
  type?: string;
  from?: string; // YYYY-MM-DD incluso
  to?: string; // YYYY-MM-DD incluso
  limit?: number;
  order?: "asc" | "desc";
}

export interface StoredRecord {
  id: string;
  type: string;
  start: string;
  end: string | null;
  date: string;
  source: string | null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: Record<string, any>;
}

const MAX_LIMIT = 2000;

/**
 * Durable Object con storage SQLite: contiene tutti i dati sanitari.
 * Esiste una sola istanza (nome "main").
 */
export class HealthStore extends DurableObject {
  private sql: SqlStorage;

  private statsReady = false;

  constructor(ctx: DurableObjectState, env: Cloudflare.Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    try {
      this.ensureSchema();
    } catch (e) {
      // Se il limite giornaliero di scritture è esaurito, creare tabelle o il riepilogo fallisce:
      // la lettura deve continuare a funzionare, quindi si riprova alla prossima occasione.
      console.warn("ensureSchema rimandato:", e);
    }
  }

  /**
   * Crea tabelle, indice e riepilogo solo se mancano. Quando è già tutto pronto esegue
   * soltanto letture, così le richieste di sola lettura non scrivono nulla.
   */
  private ensureSchema(): void {
    const existing = new Set(
      this.sql
        .exec<{ name: string }>(`SELECT name FROM sqlite_master WHERE type IN ('table', 'index')`)
        .toArray()
        .map((r) => r.name),
    );
    if (!existing.has("records") || !existing.has("idx_type_date") || !existing.has("meta") || !existing.has("type_stats")) {
      this.sql.exec(`
        CREATE TABLE IF NOT EXISTS records (
          id TEXT PRIMARY KEY,
          type TEXT NOT NULL,
          start_time TEXT NOT NULL,
          end_time TEXT,
          local_date TEXT NOT NULL,
          source TEXT,
          data TEXT NOT NULL,
          updated_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_type_date ON records(type, local_date);
        CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS type_stats (
          type TEXT PRIMARY KEY,
          count INTEGER NOT NULL,
          first_date TEXT NOT NULL,
          last_date TEXT NOT NULL
        );
      `);
    }
    // Riepilogo per tipo (usato da get_overview): calcolato una sola volta sui dati esistenti,
    // poi aggiornato a ogni sincronizzazione, così la panoramica non rilegge tutto l'archivio.
    this.statsReady = this.sql.exec(`SELECT 1 FROM meta WHERE key = 'type_stats_v1'`).toArray().length > 0;
    if (!this.statsReady) {
      this.ctx.storage.transactionSync(() => {
        this.sql.exec(`DELETE FROM type_stats`);
        this.sql.exec(
          `INSERT INTO type_stats (type, count, first_date, last_date)
           SELECT type, COUNT(*), MIN(local_date), MAX(local_date) FROM records GROUP BY type`,
        );
        this.sql.exec(`INSERT INTO meta (key, value) VALUES ('type_stats_v1', '1')`);
      });
      this.statsReady = true;
    }
  }

  async upsert(records: IncomingRecord[], deletedIds: string[] = []): Promise<{ upserted: number; deleted: number }> {
    if (!this.statsReady) this.ensureSchema();
    const now = Date.now();
    let upserted = 0;
    let deleted = 0;
    // Variazioni del riepilogo per tipo in questo invio (scritte una volta sola alla fine).
    const stats = new Map<string, { added: number; first: string; last: string }>();
    const bump = (type: string, date: string, added: number) => {
      const s = stats.get(type);
      if (!s) stats.set(type, { added, first: date, last: date });
      else {
        s.added += added;
        if (date < s.first) s.first = date;
        if (date > s.last) s.last = date;
      }
    };
    this.ctx.storage.transactionSync(() => {
      for (const r of records) {
        const data = JSON.stringify(r.data ?? {});
        const end = r.end ?? null;
        const source = r.source ?? null;
        // Record nuovo: viene inserito. Record già presente: viene riscritto solo se è cambiato,
        // così i record identici reinviati a ogni sincronizzazione non consumano il limite
        // giornaliero di rows_written del piano gratuito.
        const ins = this.sql.exec(
          `INSERT INTO records (id, type, start_time, end_time, local_date, source, data, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING`,
          r.id, r.type, r.start, end, r.date, source, data, now,
        );
        if (ins.rowsWritten > 0) {
          upserted++;
          bump(r.type, r.date, 1);
          continue;
        }
        const upd = this.sql.exec(
          `UPDATE records SET type = ?, start_time = ?, end_time = ?, local_date = ?, source = ?, data = ?, updated_at = ?
           WHERE id = ? AND (data IS NOT ? OR type IS NOT ? OR start_time IS NOT ? OR end_time IS NOT ?
                             OR local_date IS NOT ? OR source IS NOT ?)`,
          r.type, r.start, end, r.date, source, data, now,
          r.id, data, r.type, r.start, end, r.date, source,
        );
        if (upd.rowsWritten > 0) {
          upserted++;
          bump(r.type, r.date, 0);
        }
      }
      for (const id of deletedIds) {
        const row = this.sql.exec<{ type: string }>(`SELECT type FROM records WHERE id = ?`, id).toArray()[0];
        if (!row) continue;
        this.sql.exec(`DELETE FROM records WHERE id = ?`, id);
        deleted++;
        this.sql.exec(`UPDATE type_stats SET count = MAX(count - 1, 0) WHERE type = ?`, row.type);
      }
      for (const [type, s] of stats) {
        this.sql.exec(
          `INSERT INTO type_stats (type, count, first_date, last_date) VALUES (?, ?, ?, ?)
           ON CONFLICT(type) DO UPDATE SET
             count = count + excluded.count,
             first_date = MIN(first_date, excluded.first_date),
             last_date = MAX(last_date, excluded.last_date)`,
          type, s.added, s.first, s.last,
        );
      }
      this.sql.exec(
        `INSERT INTO meta (key, value) VALUES ('last_sync', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
        new Date(now).toISOString(),
      );
    });
    return { upserted, deleted };
  }

  /** Restituisce JSON (StoredRecord[]) per evitare tipi RPC complessi. */
  async query(opts: QueryOptions): Promise<string> {
    const where: string[] = [];
    const args: unknown[] = [];
    if (opts.type) {
      where.push("type = ?");
      args.push(opts.type);
    }
    if (opts.from) {
      where.push("local_date >= ?");
      args.push(opts.from);
    }
    if (opts.to) {
      where.push("local_date <= ?");
      args.push(opts.to);
    }
    const limit = Math.min(Math.max(1, opts.limit ?? 500), MAX_LIMIT);
    const order = opts.order === "desc" ? "DESC" : "ASC";
    const sql = `SELECT id, type, start_time, end_time, local_date, source, data FROM records
      ${where.length ? "WHERE " + where.join(" AND ") : ""}
      ORDER BY local_date ${order}, start_time ${order} LIMIT ${limit}`;
    // Ordinare prima per giorno sfrutta l'indice (type, local_date): con LIMIT il server
    // legge solo le righe necessarie invece di tutte quelle del periodo.
    const rows = this.sql
      .exec<{
        id: string;
        type: string;
        start_time: string;
        end_time: string | null;
        local_date: string;
        source: string | null;
        data: string;
      }>(sql, ...args)
      .toArray()
      .map((row) => ({
        id: row.id,
        type: row.type,
        start: row.start_time,
        end: row.end_time,
        date: row.local_date,
        source: row.source,
        data: JSON.parse(row.data) as Record<string, any>,
      }));
    return JSON.stringify(rows);
  }

  async overview(): Promise<{
    last_sync: string | null;
    types: { type: string; count: number; first_date: string; last_date: string }[];
  }> {
    const types = this.sql
      .exec<{ type: string; count: number; first_date: string; last_date: string }>(
        this.statsReady
          ? `SELECT type, count, first_date, last_date FROM type_stats WHERE count > 0 ORDER BY type`
          : `SELECT type, COUNT(*) AS count, MIN(local_date) AS first_date, MAX(local_date) AS last_date
             FROM records GROUP BY type ORDER BY type`,
      )
      .toArray();
    const meta = this.sql.exec<{ value: string }>(`SELECT value FROM meta WHERE key = 'last_sync'`).toArray();
    return { last_sync: meta[0]?.value ?? null, types };
  }
}
