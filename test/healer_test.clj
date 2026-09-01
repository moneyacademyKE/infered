(ns healer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-json-syntax-healing
  (testing "Repairs malformed JSON strings produced by budget LLMs"
    (let [res (run-node-eval
               "import { repairJsonString } from './src/router/healer.js';

                // 1. Markdown code fences and single quotes
                const broken1 = \"```json\\n{'command': 'ls -la', 'timeout': '30',}\\n```\";
                const healed1 = JSON.parse(repairJsonString(broken1));

                // 2. Unclosed braces/brackets from token cutoffs
                const broken2 = '{\"items\": [{\"id\": 1, \"name\": \"item1\"}, {\"id\": 2';
                const healed2 = JSON.parse(repairJsonString(broken2));

                // 3. Trailing commas and unescaped newlines
                const broken3 = '{\"query\": \"select * from users\\nwhere active=1\", \"limit\": \"50\", }';
                const healed3 = JSON.parse(repairJsonString(broken3));

                console.log(JSON.stringify({
                  cmd: healed1.command,
                  itemsCount: healed2.items.length,
                  queryPresent: healed3.query.includes('select')
                }));")]
      (is (= "ls -la" (:cmd res)))
      (is (>= (:itemsCount res) 1))
      (is (:queryPresent res)))))

(deftest test-schema-coercion-and-fuzzy-keys
  (testing "Coerces types and remaps fuzzy parameter keys to canonical schema"
    (let [res (run-node-eval
               "import { healToolCalls } from './src/router/healer.js';

                const toolSchema = [
                  {
                    type: 'function',
                    function: {
                      name: 'read_file',
                      parameters: {
                        type: 'object',
                        properties: {
                          target_file: { type: 'string' },
                          start_line: { type: 'integer' },
                          end_line: { type: 'integer' },
                          include_hidden: { type: 'boolean' }
                        },
                        required: ['target_file', 'start_line']
                      }
                    }
                  }
                ];

                // LLM outputted string numbers, string boolean, and fuzzy key 'filePath'
                const rawChoices = [
                  {
                    index: 0,
                    message: {
                      role: 'assistant',
                      tool_calls: [
                        {
                          id: 'call_123',
                          type: 'function',
                          function: {
                            name: 'read_file',
                            arguments: '{\"filePath\": \"/app/src/main.js\", \"start_line\": \"10\", \"end_line\": \"50\", \"include_hidden\": \"true\"}'
                          }
                        }
                      ]
                    }
                  }
                ];

                const healed = healToolCalls(rawChoices, toolSchema);
                const healedArgs = JSON.parse(healed[0].message.tool_calls[0].function.arguments);

                console.log(JSON.stringify({
                  targetFile: healedArgs.target_file,
                  startLine: healedArgs.start_line,
                  startLineIsNumber: typeof healedArgs.start_line === 'number',
                  endLineIsNumber: typeof healedArgs.end_line === 'number',
                  includeHiddenIsBool: typeof healedArgs.include_hidden === 'boolean',
                  includeHiddenValue: healedArgs.include_hidden
                }));")]
      (is (= "/app/src/main.js" (:targetFile res)))
      (is (= 10 (:startLine res)))
      (is (:startLineIsNumber res))
      (is (:endLineIsNumber res))
      (is (:includeHiddenIsBool res))
      (is (true? (:includeHiddenValue res))))))
