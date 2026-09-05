/**
 * /analytics — routing decision analytics as a simple checklist.
 * Reads what recordRoutingAnalytics persisted in D1 (routing_decisions).
 * Server-rendered HTML: no client JS, no frameworks, one db.batch() round trip.
 */

const TOP_MODELS_SQL = `
  SELECT selected_model AS model,
         COUNT(*) AS reqs,
         CAST(100.0 * SUM(ok) / COUNT(*) AS INT) AS okPct,
         CAST(AVG(latency_ms) AS INT) AS avgMs,
         ROUND(AVG(attempts), 2) AS avgAttempts
  FROM routing_decisions
  GROUP BY selected_model
  ORDER BY reqs DESC
  LIMIT 8`;

const SWITCHING_SQL = `
  SELECT COUNT(*) AS total,
         SUM(CASE WHEN attempts = 1 THEN 1 ELSE 0 END) AS directHits,
         SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS failovers,
         SUM(CASE WHEN attempts = 0 THEN 1 ELSE 0 END) AS errors,
         SUM(CASE WHEN escalation_level > 0 THEN 1 ELSE 0 END) AS escalations,
         ROUND(AVG(CASE WHEN attempts > 0 THEN attempts END), 2) AS avgAttempts
  FROM routing_decisions`;

const FLAPPING_SQL = `
  SELECT COUNT(*) AS sessions FROM (
    SELECT session_id FROM routing_decisions
    WHERE session_id IS NOT NULL
    GROUP BY session_id
    HAVING COUNT(DISTINCT selected_model) > 1)`;

const HEALTH_SQL = `
  SELECT COUNT(DISTINCT session_id) AS sessions,
         SUM(CASE WHEN ts >= datetime('now', '-1 day') THEN 1 ELSE 0 END) AS last24h
  FROM routing_decisions`;

// Per-requested-chain view: the chain's own success rate, invisible in
// TOP_MODELS_SQL because successes there are grouped by the SERVED model.
const CHAINS_SQL = `
  SELECT requested_model AS chain,
         COUNT(*) AS reqs,
         CAST(100.0 * SUM(ok) / COUNT(*) AS INT) AS okPct,
         SUM(CASE WHEN attempts = 1 AND ok = 1 THEN 1 ELSE 0 END) AS directHits,
         ROUND(AVG(CASE WHEN attempts > 0 THEN attempts END), 2) AS avgAttempts
  FROM routing_decisions
  WHERE requested_model IS NOT NULL
  GROUP BY requested_model
  ORDER BY reqs DESC
  LIMIT 8`;

export async function collectAnalytics(db) {
  const [top, switching, flapping, health, chains] = await db.batch([
    db.prepare(TOP_MODELS_SQL),
    db.prepare(SWITCHING_SQL),
    db.prepare(FLAPPING_SQL),
    db.prepare(HEALTH_SQL),
    db.prepare(CHAINS_SQL)
  ]);
  return {
    topModels: top.results || [],
    switching: (switching.results || [])[0] || {},
    flappingSessions: (flapping.results || [])[0]?.sessions || 0,
    totalSessions: (health.results || [])[0]?.sessions || 0,
    last24h: (health.results || [])[0]?.last24h || 0,
    chains: chains.results || []
  };
}

const STYLE = `
  body{font-family:ui-monospace,Menlo,monospace;background:#0d1117;color:#c9d1d9;
       max-width:720px;margin:40px auto;padding:0 20px;line-height:1.6}
  h1{font-size:1.2rem;color:#58a6ff} h2{font-size:1rem;margin-top:28px}
  .item{padding:4px 0;border-bottom:1px solid #21262d}
  .ok{color:#3fb950}.bad{color:#f85149}.muted{color:#8b949e;font-size:.85rem}
  .box{display:inline-block;width:1.1em}.num{color:#e3b341}`;

function check(cond) {
  return cond ? '<span class="ok">☑</span>' : '<span class="bad">☒</span>';
}

export function renderAnalyticsPage(d, ratecard = null) {
  const s = d.switching;
  const total = s.total || 0;
  const directPct = total ? Math.round(100 * (s.directHits || 0) / total) : 0;
  const stable = d.flappingSessions === 0;

  const priceRows = Array.isArray(ratecard) && ratecard.length
    ? ratecard.map(r => {
        const spot = r.spot;
        const live = Boolean(spot);
        const verdict = live && (spot.savingsPct ?? 0) >= 90;
        return `<div class="item">${check(verdict)} <strong>${r.modelId}</strong>
         <span class="num">$${r.blended}</span>/M list · ${live
          ? `<span class="num">$${spot.blendedPrice}</span> spot · ${spot.savingsPct}% off`
          : `<span class="muted">no live asks</span>`}${r.calibrated ? "" : ` · <span class="muted">uncalibrated</span>`}</div>`;
      }).join("")
    : `<div class="item muted">ratecard unavailable</div>`;

  const modelRows = d.topModels.length
    ? d.topModels.map(m =>
        `<div class="item">${check((m.okPct ?? 0) >= 95)} <strong>${m.model}</strong>
         <span class="num">${m.reqs}</span> reqs · ${m.avgMs ?? "?"}ms avg · ${m.okPct ?? 0}% ok · ${m.avgAttempts ?? 1} attempts</div>`).join("")
    : `<div class="item muted">no decision rows yet — send a completion</div>`;

  return `<!doctype html><html><head><meta charset="utf-8">
<title>infered · routing analytics</title><style>${STYLE}</style></head><body>
<h1>☑ infered routing analytics</h1>
<p class="muted">${total} decisions · ${d.totalSessions} sessions · ${d.last24h} in last 24h · free-tier D1</p>

<h2>Top performing models</h2>
${modelRows}

<h2>Switching effectiveness</h2>
<div class="item">${check(directPct >= 90)} direct hits (1 attempt, no failover): <span class="num">${directPct}%</span></div>
<div class="item">${check((s.avgAttempts ?? 1) <= 1.2)} avg attempts per request: <span class="num">${s.avgAttempts ?? "–"}</span></div>
<div class="item">↻ silent failovers absorbed: <span class="num">${s.failovers || 0}</span></div>
<div class="item">▲ budget escalations fired: <span class="num">${s.escalations || 0}</span></div>
<div class="item">✗ total failures recorded: <span class="num">${s.errors || 0}</span></div>

<h2>Session stickiness</h2>
<div class="item">${check(stable)} sessions that changed model mid-flight: <span class="num">${d.flappingSessions}</span></div>

<h2>Chains (requested → served)</h2>
${d.chains.length
    ? d.chains.map(c =>
        `<div class="item">${check((c.okPct ?? 0) >= 95)} <strong>${c.chain}</strong>
         <span class="num">${c.reqs}</span> reqs · ${c.okPct ?? 0}% ok · ${c.directHits || 0} direct · ${c.avgAttempts ?? "–"} avg attempts</div>`).join("")
    : `<div class="item muted">no chain rows yet (pre-attribution decisions have no requested_model)</div>`}

<h2>Prices (list → live spot)</h2>
${priceRows}
</body></html>`;
}
