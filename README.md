# ⚡ Infered: Dynamic Virtual LLM Router for Token Marketplaces

**Infered** is a Cloudflare Workers edge router that dynamically selects the highest quality LLM on InferHub token marketplaces at spot prices with sub-millisecond Pareto scoring, zero-downtime budget escalation, multi-tier edge caching, and self-healing tool calls.

---

## 🚀 Quickstart: Configure Your Coding Agent

Infered exposes a standard OpenAI-compatible API endpoint:
```
https://infered-virtual-router.moneyacad.workers.dev/v1
```

### 1. Cursor IDE
1. Open **Cursor Settings** (`Cmd + ,`) $\rightarrow$ **Models**.
2. Enable **Override OpenAI Base URL**:
   - **Base URL**: `https://infered-virtual-router.moneyacad.workers.dev/v1`
   - **API Key**: `YOUR_INFERHUB_API_KEY`
3. Click **Add Model** and enter: `infered/sol-budget`
4. Select `infered/sol-budget` as your active model.

---

### 2. Aider CLI
Add to `.aider.conf.yml` in your project or home directory:
```yaml
openai-api-base: https://infered-virtual-router.moneyacad.workers.dev/v1
openai-api-key: YOUR_INFERHUB_API_KEY
model: openai/infered/sol-budget
edit-format: diff
```

Or run via CLI:
```bash
aider --openai-api-base https://infered-virtual-router.moneyacad.workers.dev/v1 \
      --openai-api-key YOUR_INFERHUB_API_KEY \
      --model openai/infered/sol-budget
```

---

### 3. Cline / Roo-Code (VS Code Extension)
1. Open **Cline Settings** $\rightarrow$ API Provider: **OpenAI Compatible**.
2. **Base URL**: `https://infered-virtual-router.moneyacad.workers.dev/v1`
3. **API Key**: `YOUR_INFERHUB_API_KEY`
4. **Model ID**: `infered/sol-budget`
5. *(Optional)* Custom Headers:
   - `X-Session-ID`: `cline-dev-session`
   - `X-Infered-Max-Price`: `0.10`

---

### 4. Continue.dev
In `~/.continue/config.json`:
```json
{
  "models": [
    {
      "title": "Infered Sol Budget",
      "provider": "openai",
      "model": "infered/sol-budget",
      "apiBase": "https://infered-virtual-router.moneyacad.workers.dev/v1",
      "apiKey": "YOUR_INFERHUB_API_KEY"
    }
  ]
}
```

---

### 5. Python / OpenAI SDK
```python
from openai import OpenAI

client = OpenAI(
    base_url="https://infered-virtual-router.moneyacad.workers.dev/v1",
    api_key="YOUR_INFERHUB_API_KEY",
    default_headers={"X-Session-ID": "agent-session-1"}
)

response = client.chat.completions.create(
    model="infered/sol-budget",
    messages=[{"role": "user", "content": "Write a Babashka test script."}]
)
print(response.choices[0].message.content)
```

---

## 🎯 Virtual Model Aliases

| Virtual Model Alias | Strategy & Behavior |
| :--- | :--- |
| **`infered/sol-budget`** | **Primary**: `cx/gpt-5.6-sol`. Cascades to `zai/glm-5.3-flash` $\rightarrow$ `zai/glm-5.3` $\rightarrow$ `ali/kimi-k3` $\rightarrow$ `cx/gpt-5.6-terra` ($\le \$0.10 \rightarrow \$0.20 \rightarrow \$0.30$). |
| **`infered/cascade`** | Ordered fallback cascade across all models with progressive budget escalation. |
| **`infered/auto`** | Multi-objective Pareto routing across all available models. |
| **`infered/cost-optimized`** | Highest weight on cheapest spot asks ($w_p = 0.8$). |
| **`infered/speed-first`** | Routes to lowest EMA latency & TTFT nodes ($w_s = 0.8$). |
| **`infered/quality-first`** | Prioritizes frontier capability models ($w_q = 0.7$). |

---

## 🛡️ Core Capabilities

1. **Multi-Objective Pareto Engine**: Evaluates $U(m) = w_p S_p + w_s S_s + w_q S_q + B_{\text{affinity}} - P_{\text{error}}$.
2. **Elastic Budget Ladder**: Dynamically steps through $\$0.10 \rightarrow \$0.20 \rightarrow \$0.30 \rightarrow \text{unconstrained fallback}$ during spot price spikes for 100% zero-downtime SLA.
3. **Multi-Tier Edge Caching**:
   - $T=0$ exact response match returned in **<1ms** with **100% token savings**.
   - `X-Session-ID` KV prefix cache affinity preserving 80–90% prompt token discounts on GPU nodes.
   - SWR background order book synchronization.
4. **Deterministic Tool Call Semantic Sieve**:
   - Autoheals broken JSON syntax, markdown fences, unclosed brackets, and unescaped control characters in <0.5ms.
   - Coerces stringified primitives and remaps fuzzy parameter keys to canonical tool schemas.
   - Synthesizes 1-shot RFC 8259 exemplars for budget models.

---

## 🧪 Verification & Testing

Using **Babashka** (`bb`):
```bash
bb test-all    # Run all 8 test suites (60+ assertions)
bb sim         # Run 100-cycle market volatility simulation
bb lint        # Verify all source files < 500 LOC
```

---

## 🚢 Deployment

Deploy to Cloudflare Workers with Wrangler:
```bash
bunx wrangler deploy
```

---

## 📜 License
MIT
