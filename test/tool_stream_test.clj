(ns tool-stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(def harness
  "import { executeWithFallback } from './src/router/client.js';
   import { createMetricsStore } from './src/router/metrics.js';
   const metricsStore = createMetricsStore();
   const enc = new TextEncoder();
   const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';
   const toolStart = sse({ tool_calls: [{ index: 0, id: 'call_ping', type: 'function', function: { name: 'ping', arguments: '' } }] });
   const toolArgs = sse({ tool_calls: [{ index: 0, function: { arguments: '{\"value\":\"ok\"}' } }] });
   const toolFinish = 'data: ' + JSON.stringify({ choices: [{ delta: {}, finish_reason: 'tool_calls' }] }) + '\\n\\n';
   function badFrames(received) {
     let bad = 0;
     for (const event of received.split(/\\r?\\n\\r?\\n/)) {
       const lines = event.split(/\\r?\\n/).filter(l => l.startsWith('data:'));
       if (!lines.length) continue;
       const payload = lines.map(l => l.slice(5).trim()).join('\\n');
       if (payload === '[DONE]' || !payload) continue;
       try { JSON.parse(payload); } catch { bad++; }
     }
     return bad;
   }
   async function drain(result) {
     const reader = result.stream.getReader();
     const dec = new TextDecoder();
     let received = '';
     try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
     await new Promise(r => setTimeout(r, 30));
     return received;
   }")

(deftest test-tool-only-completion-is-not-empty
  (testing "reasoning/tool_calls without text content is a valid completion: relay it and terminate cleanly"
    (let [res (run-node-eval
               (str harness
                    "const fetchFn = async () => new Response(new ReadableStream({
                       async start(c) {
                         c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                         await new Promise(r => setTimeout(r, 5));
                         c.enqueue(enc.encode(toolStart));
                         c.enqueue(enc.encode(toolArgs));
                         c.enqueue(enc.encode(toolFinish));
                         c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                         c.close();
                       }
                     }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                     const result = await executeWithFallback({
                       candidates: [{ providerId: 'tool-node', modelId: 'ali/kimi-k3.1-pro', savingsPct: 98, blendedPrice: 0.19 }],
                       requestBody: { stream: true, messages: [{ role: 'user', content: 'call ping' }] },
                       apiKey: 'test-key', metricsStore, fetchFn
                     });
                     const received = await drain(result);
                     console.log(JSON.stringify({
                       success: result.success,
                       hasTool: received.includes('call_ping'),
                       hasDone: (received.match(/\\[DONE\\]/g) || []).length === 1,
                       syntheticError: received.includes('all candidates exhausted before content'),
                       badFrames: badFrames(received),
                       attempts: result.getMetrics().attempts,
                       emptyCorpses: metricsStore.usage.emptyDoneCorpses || 0
                     }));"))]
      (is (:success res))
      (is (:hasTool res) "the tool call reaches the client")
      (is (:hasDone res) "exactly one terminator")
      (is (false? (:syntheticError res)) "a tool-only answer must not be declared an empty corpse")
      (is (zero? (:badFrames res)) "every SSE data frame is valid JSON")
      (is (= 1 (:attempts res)))
      (is (zero? (:emptyCorpses res))))))

(deftest test-truncated-tail-cannot-poison-tool-stream
  (testing "valid tool_calls followed by an incomplete usage frame and EOF: drop the tail, synthesize DONE"
    (let [res (run-node-eval
               (str harness
                    "const fetchFn = async () => new Response(new ReadableStream({
                       async start(c) {
                         c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                         c.enqueue(enc.encode(toolStart));
                         c.enqueue(enc.encode(toolArgs));
                         c.enqueue(enc.encode(toolFinish));
                         c.enqueue(enc.encode('data: {\"choices\":[],\"object\":\"chat.completion.chunk\",'));
                         c.close();
                       }
                     }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                     const result = await executeWithFallback({
                       candidates: [{ providerId: 'tool-node', modelId: 'ali/kimi-k3.1-pro', savingsPct: 98, blendedPrice: 0.19 }],
                       requestBody: { stream: true, messages: [{ role: 'user', content: 'call ping' }] },
                       apiKey: 'test-key', metricsStore, fetchFn
                     });
                     const received = await drain(result);
                     console.log(JSON.stringify({
                       success: result.success,
                       hasTool: received.includes('call_ping'),
                       hasDone: (received.match(/\\[DONE\\]/g) || []).length === 1,
                       syntheticError: received.includes('all candidates exhausted before content'),
                       badFrames: badFrames(received),
                       synthesized: metricsStore.usage.doneSynthesized || 0,
                       attempts: result.getMetrics().attempts
                     }));"))]
      (is (:success res))
      (is (:hasTool res))
      (is (:hasDone res) "the router supplies the missing terminator")
      (is (false? (:syntheticError res)))
      (is (zero? (:badFrames res)) "the incomplete upstream tail must never be relayed")
      (is (= 1 (:synthesized res)))
      (is (= 1 (:attempts res))))))
