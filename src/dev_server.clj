(ns dev-server
  (:require [clojure.java.shell :refer [sh]]
            [babashka.process :as p]))

(println "==========================================================")
(println "🚀 STARTING INFERED LOCAL CLOUDFLARE WORKERS SERVER 🚀")
(println "==========================================================")
(println "Port: http://localhost:8787")
(println "Router homepage: http://localhost:8787/")
(println "OpenAI Proxy:  http://localhost:8787/v1/chat/completions")
(println "Health Check:  http://localhost:8787/v1/health")
(println "Metrics:       http://localhost:8787/v1/metrics")
(println "----------------------------------------------------------")

(let [server-code
      "import http from 'http';
       import worker from './src/worker.js';

       const PORT = process.env.PORT || 8787;
       const env = {
         INFERHUB_BASE_URL: process.env.INFERHUB_BASE_URL || 'https://api.inferhub.net/v1',
         ROUTING_POLICY: process.env.ROUTING_POLICY || 'balanced',
         PRICE_WEIGHT: process.env.PRICE_WEIGHT || '0.5',
         SPEED_WEIGHT: process.env.SPEED_WEIGHT || '0.3',
         QUALITY_WEIGHT: process.env.QUALITY_WEIGHT || '0.2'
       };
       const ctx = { waitUntil: (promise) => promise.catch(console.error) };

       const server = http.createServer(async (req, res) => {
         try {
           const chunks = [];
           for await (const chunk of req) chunks.push(chunk);
           const bodyBuffer = Buffer.concat(chunks);
           const body = ['GET', 'HEAD', 'OPTIONS'].includes(req.method) ? undefined : bodyBuffer;

           const headers = new Headers();
           for (const [k, v] of Object.entries(req.headers)) {
             if (v) headers.set(k, Array.isArray(v) ? v.join(', ') : v);
           }

           const fullUrl = 'http://' + (req.headers.host || 'localhost:8787') + req.url;
           const cfRequest = new Request(fullUrl, {
             method: req.method,
             headers,
             body
           });

           const cfResponse = await worker.fetch(cfRequest, env, ctx);

           res.statusCode = cfResponse.status;
           for (const [k, v] of cfResponse.headers.entries()) {
             res.setHeader(k, v);
           }

           if (cfResponse.body) {
             const reader = cfResponse.body.getReader();
             while (true) {
               const { done, value } = await reader.read();
               if (done) break;
               res.write(value);
             }
             res.end();
           } else {
             res.end();
           }
         } catch (err) {
           console.error('Server error:', err);
           res.statusCode = 500;
           res.setHeader('Content-Type', 'application/json');
           res.end(JSON.stringify({ error: err.message }));
         }
       });

       server.listen(PORT, () => {
         console.log(`[Infered] Server listening on http://localhost:${PORT}`);
       });"]
  ;; Run server process
  (p/shell "node" "--input-type=module" "-e" server-code))
