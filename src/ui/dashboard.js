/**
 * Embedded Zero-NPM Reactive Web UI
 * Serves modern, glassmorphic monitoring dashboard and interactive test bench directly from the edge.
 */

export function renderDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Infered — Dynamic Virtual LLM Router</title>
  <style>
    :root {
      --bg: #090d16;
      --card-bg: rgba(22, 30, 49, 0.75);
      --card-border: rgba(255, 255, 255, 0.08);
      --accent: #6366f1;
      --accent-hover: #4f46e5;
      --emerald: #10b981;
      --amber: #f59e0b;
      --rose: #f43f5e;
      --cyan: #06b6d4;
      --text: #f1f5f9;
      --text-muted: #94a3b8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 24px; }
    .container { max-width: 1280px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }
    header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--card-border); }
    .logo-group { display: flex; align-items: center; gap: 12px; }
    .logo-badge { background: linear-gradient(135deg, var(--accent), var(--cyan)); color: #fff; font-weight: 800; padding: 6px 14px; border-radius: 8px; font-size: 1.1rem; }
    .title-desc h1 { font-size: 1.4rem; font-weight: 700; }
    .title-desc p { font-size: 0.85rem; color: var(--text-muted); }
    .edge-badge { background: rgba(16, 185, 129, 0.15); color: var(--emerald); border: 1px solid rgba(16, 185, 129, 0.3); padding: 4px 10px; border-radius: 9999px; font-size: 0.75rem; font-weight: 600; }

    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
    .stat-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 18px; backdrop-filter: blur(12px); }
    .stat-label { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.5px; }
    .stat-value { font-size: 1.6rem; font-weight: 700; }
    .stat-sub { font-size: 0.75rem; color: var(--emerald); margin-top: 4px; }

    .main-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    @media (max-width: 900px) { .main-grid { grid-template-columns: 1fr; } }

    .panel { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 20px; backdrop-filter: blur(12px); display: flex; flex-direction: column; gap: 16px; }
    .panel-title { font-size: 1.1rem; font-weight: 600; display: flex; justify-content: space-between; align-items: center; }

    .slider-group { display: flex; flex-direction: column; gap: 12px; background: rgba(0,0,0,0.2); padding: 14px; border-radius: 8px; }
    .slider-row { display: flex; justify-content: space-between; align-items: center; }
    .slider-row label { font-size: 0.85rem; color: var(--text-muted); }
    .slider-row input[type="range"] { width: 55%; accent-color: var(--accent); }
    .slider-val { font-size: 0.85rem; font-weight: 600; min-width: 35px; text-align: right; }

    .form-group { display: flex; flex-direction: column; gap: 6px; }
    label { font-size: 0.85rem; color: var(--text-muted); }
    select, textarea, input { background: rgba(0,0,0,0.3); border: 1px solid var(--card-border); border-radius: 6px; padding: 10px; color: var(--text); font-size: 0.9rem; }
    select:focus, textarea:focus, input:focus { outline: none; border-color: var(--accent); }
    button { background: var(--accent); color: white; border: none; padding: 10px 16px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: background 0.15s; }
    button:hover { background: var(--accent-hover); }

    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th { text-align: left; padding: 8px 10px; color: var(--text-muted); border-bottom: 1px solid var(--card-border); }
    td { padding: 10px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); }
    .badge { padding: 2px 8px; border-radius: 4px; font-size: 0.75rem; font-weight: 600; }
    .badge-green { background: rgba(16, 185, 129, 0.2); color: var(--emerald); }
    .badge-blue { background: rgba(99, 102, 241, 0.2); color: #818cf8; }
    .badge-amber { background: rgba(245, 158, 11, 0.2); color: var(--amber); }

    .output-box { background: #000; border: 1px solid var(--card-border); border-radius: 8px; padding: 12px; font-family: monospace; font-size: 0.85rem; min-height: 140px; max-height: 240px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo-group">
        <div class="logo-badge">INFERED</div>
        <div class="title-desc">
          <h1>Virtual LLM Router for InferHub</h1>
          <p>Cloudflare Workers Edge • Real-Time Dynamic Spot Arbitrage &amp; Budget Cascades</p>
        </div>
      </div>
      <div class="edge-badge">● Edge Online (330+ PoPs)</div>
    </header>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">Average Savings</div>
        <div class="stat-value" id="avg-savings">69.4%</div>
        <div class="stat-sub">vs Official List Price Baselines</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Edge Decision Overhead</div>
        <div class="stat-value" id="edge-latency">&lt; 1.8 ms</div>
        <div class="stat-sub">Zero-Buffer Cloudflare V8</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Cascade Policy</div>
        <div class="stat-value" style="font-size: 1.2rem; color: var(--cyan);">Sol &rarr; Flash &rarr; GLM &rarr; Kimi &rarr; Terra</div>
        <div class="stat-sub">Strict Budget Ceiling &le; $0.10 / 1M</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Active Market Quotes</div>
        <div class="stat-value" id="quote-count">54 Nodes</div>
        <div class="stat-sub">Live InferHub Spot Marketplace</div>
      </div>
    </div>

    <div class="main-grid">
      <div class="panel">
        <div class="panel-title">
          <span>⚡ Live Query Dispatcher</span>
          <span style="font-size:0.8rem; color:var(--text-muted);">OpenAI-Compatible</span>
        </div>

        <div class="slider-group">
          <div style="font-size:0.8rem; font-weight:600; color:var(--text-muted);">PARETO OBJECTIVES &amp; BUDGET CEILING</div>
          <div class="slider-row">
            <label>Price Savings Weight ($w_p$)</label>
            <input type="range" id="w-price" min="0" max="1" step="0.05" value="0.5">
            <span class="slider-val" id="val-price">0.50</span>
          </div>
          <div class="slider-row">
            <label>Speed / TTFT Weight ($w_s$)</label>
            <input type="range" id="w-speed" min="0" max="1" step="0.05" value="0.3">
            <span class="slider-val" id="val-speed">0.30</span>
          </div>
          <div class="slider-row">
            <label>Fallback Max Price Ceiling ($)</label>
            <input type="number" id="max-price-input" min="0.01" max="5.0" step="0.01" value="0.10" style="width:75px; padding:4px;">
            <span style="font-size:0.8rem; color:var(--text-muted);">/ 1M</span>
          </div>
        </div>

        <div class="form-group">
          <label>Target Model / Policy</label>
          <select id="model-select">
            <option value="infered/sol-budget">infered/sol-budget (cx/gpt-5.6-sol &rarr; glm-flash &rarr; glm &rarr; kimi &rarr; terra &le; $0.10)</option>
            <option value="infered/auto">infered/auto (Optimal Global Pareto)</option>
            <option value="infered/fast">infered/fast (Lowest TTFT: GLM-5.3-Flash, Llama 3.1 8B)</option>
            <option value="infered/smart">infered/smart (GPT-5.6 Terra, GLM-5.3, Claude 3.5 Sonnet)</option>
            <option value="infered/reasoning">infered/reasoning (GPT-5.6 Sol, Kimi K3, DeepSeek R1)</option>
            <option value="infered/cheap">infered/cheap (Maximum Discount Spot Nodes)</option>
            <option value="cx/gpt-5.6-sol">cx/gpt-5.6-sol (Direct Auto Spot Node)</option>
            <option value="zai/glm-5.3-flash">zai/glm-5.3-flash (Direct Auto Spot Node)</option>
          </select>
        </div>

        <div class="form-group">
          <label>Prompt</label>
          <textarea id="prompt-input" rows="2">Analyze the computational trade-offs of sparse linear attention.</textarea>
        </div>

        <button id="send-btn" onclick="sendTestRequest()">Route &amp; Execute Request</button>
        <div class="output-box" id="result-box">Ready. Dispatch a request to see budget cascade in action.</div>
      </div>

      <div class="panel">
        <div class="panel-title">
          <span>📊 Live Marketplace Spot Ticker</span>
          <button onclick="refreshData()" style="padding:4px 10px; font-size:0.75rem;">↻ Refresh</button>
        </div>

        <div style="max-height: 480px; overflow-y: auto;">
          <table>
            <thead>
              <tr>
                <th>Model</th>
                <th>Provider Node</th>
                <th>Spot Price</th>
                <th>Savings</th>
                <th>EMA Latency</th>
              </tr>
            </thead>
            <tbody id="quotes-tbody">
              <tr><td colspan="5" style="text-align:center; color:var(--text-muted);">Loading quotes...</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <script>
    ['price', 'speed'].forEach(k => {
      const slider = document.getElementById('w-' + k);
      const valSpan = document.getElementById('val-' + k);
      slider.oninput = () => { valSpan.textContent = Number(slider.value).toFixed(2); };
    });

    async function refreshData() {
      try {
        const res = await fetch('/v1/metrics');
        const data = await res.json();
        if (data.avgSavingsPct) document.getElementById('avg-savings').textContent = data.avgSavingsPct + '%';
        if (data.totalQuotes) document.getElementById('quote-count').textContent = data.totalQuotes + ' Nodes';
        
        const tbody = document.getElementById('quotes-tbody');
        if (data.quotes && data.quotes.length > 0) {
          tbody.innerHTML = data.quotes.slice(0, 18).map(q => {
            const savingsClass = q.savingsPct > 50 ? 'badge-green' : 'badge-amber';
            const priceStyle = q.blendedPrice <= 0.10 ? 'color: var(--emerald); font-weight:600;' : '';
            return \`<tr>
              <td><strong>\${q.modelId}</strong></td>
              <td><span class="badge badge-blue">\${q.providerId}</span></td>
              <td style="\${priceStyle}">\$\${q.blendedPrice.toFixed(3)} / 1M</td>
              <td><span class="badge \${savingsClass}">-\${q.savingsPct}%</span></td>
              <td>\${q.emaLatency || 200} ms</td>
            </tr>\`;
          }).join('');
        }
      } catch (err) {
        console.error('Failed to load metrics', err);
      }
    }

    async function sendTestRequest() {
      const model = document.getElementById('model-select').value;
      const prompt = document.getElementById('prompt-input').value;
      const maxPrice = document.getElementById('max-price-input').value;
      const resultBox = document.getElementById('result-box');
      const btn = document.getElementById('send-btn');

      btn.disabled = true;
      resultBox.textContent = '⚡ Routing request across InferHub nodes...';

      const t0 = performance.now();
      try {
        const res = await fetch('/v1/chat/completions', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Infered-Max-Price': maxPrice,
            'X-Infered-Weights': JSON.stringify({
              price: parseFloat(document.getElementById('w-price').value),
              speed: parseFloat(document.getElementById('w-speed').value),
              quality: 0.2
            })
          },
          body: JSON.stringify({
            model,
            messages: [{ role: 'user', content: prompt }],
            stream: false
          })
        });

        const elapsed = (performance.now() - t0).toFixed(1);
        const selectedModel = res.headers.get('x-infered-selected-model') || 'N/A';
        const provider = res.headers.get('x-infered-provider') || 'N/A';
        const savings = res.headers.get('x-infered-savings-pct') || '0';
        const nodeLatency = res.headers.get('x-infered-latency-ms') || 'N/A';

        const json = await res.json();
        const text = json.choices && json.choices[0] ? json.choices[0].message.content : JSON.stringify(json, null, 2);

        resultBox.textContent = \`[ROUTING DECISION]
• Target Policy:        \${model}
• Selected Spot Model:  \${selectedModel}
• Chosen Node Provider: \${provider}
• Token Savings:        \${savings}% vs official list price
• Node Latency:         \${nodeLatency} ms (Total roundtrip: \${elapsed} ms)

[RESPONSE]
\${text}\`;
        refreshData();
      } catch (err) {
        resultBox.textContent = '❌ Error executing request: ' + err.message;
      } finally {
        btn.disabled = false;
      }
    }

    refreshData();
  </script>
</body>
</html>`;
}
