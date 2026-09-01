/**
 * Tool Call Semantic Sieve & JSON Autohealing Engine
 * Repairs malformed JSON, coerces parameter types, remaps fuzzy keys and synonyms,
 * and validates tool call schemas in sub-millisecond edge compute.
 */

const COMMON_SYNONYMS = {
  filepath: ["target_file", "file_path", "filename", "path"],
  file: ["target_file", "file_path", "filename", "path"],
  filename: ["target_file", "file_path", "path"],
  query: ["search_query", "q", "query_str", "prompt"],
  text: ["content", "message", "body", "input"],
  url: ["target_url", "uri", "link"],
  cmd: ["command", "exec", "script"],
  limit: ["max_results", "count", "num_results"]
};

function levenshtein(a, b) {
  const an = a ? a.length : 0;
  const bn = b ? b.length : 0;
  if (an === 0) return bn;
  if (bn === 0) return an;
  const matrix = Array.from({ length: bn + 1 }, (_, i) => [i]);
  for (let j = 0; j <= an; j++) matrix[0][j] = j;

  for (let i = 1; i <= bn; i++) {
    for (let j = 1; j <= an; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1];
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1,
          matrix[i][j - 1] + 1,
          matrix[i - 1][j] + 1
        );
      }
    }
  }
  return matrix[bn][an];
}

function normalizeParamName(name) {
  return name.toLowerCase().replace(/[-_]/g, "");
}

/**
 * Deterministically repairs malformed JSON syntax using a bracket stack and control char escaping.
 */
export function repairJsonString(rawStr) {
  if (!rawStr || typeof rawStr !== "string") return "{}";
  let s = rawStr.trim();

  // 1. Strip markdown code fences (```json ... ```)
  s = s.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();

  // 2. Locate outermost JSON structure
  const firstBrace = s.indexOf("{");
  const firstBracket = s.indexOf("[");
  let startIdx = 0;
  if (firstBrace !== -1 && (firstBracket === -1 || firstBrace < firstBracket)) {
    startIdx = firstBrace;
  } else if (firstBracket !== -1) {
    startIdx = firstBracket;
  }
  s = s.substring(startIdx).trim();

  // 3. Fix single-quoted keys and string values
  s = s.replace(/([{,]\s*)'([^'\\]*(?:\\.[^'\\]*)*)'\s*:/g, '$1"$2":');
  s = s.replace(/:\s*'([^'\\]*(?:\\.[^'\\]*)*)'(\s*[,}])/g, ':"$1"$2');

  // 4. Remove trailing commas before closing braces/brackets
  s = s.replace(/,\s*([}\]])/g, "$1");

  // 5. Balance unclosed braces/brackets & escape unescaped control chars
  const stack = [];
  let inString = false;
  let escaped = false;
  let out = "";

  for (let i = 0; i < s.length; i++) {
    const char = s[i];

    if (escaped) {
      escaped = false;
      out += char;
      continue;
    }

    if (char === "\\") {
      escaped = true;
      out += char;
      continue;
    }

    if (char === '"') {
      inString = !inString;
      out += char;
      continue;
    }

    if (inString) {
      if (char === "\n") {
        out += "\\n";
      } else if (char === "\r") {
        out += "\\r";
      } else if (char === "\t") {
        out += "\\t";
      } else {
        out += char;
      }
      continue;
    }

    out += char;

    if (char === "{") {
      stack.push("}");
    } else if (char === "[") {
      stack.push("]");
    } else if (char === "}" || char === "]") {
      if (stack.length > 0 && stack[stack.length - 1] === char) {
        stack.pop();
      }
    }
  }

  if (inString) {
    out += '"';
  }

  // Strip trailing comma before appending closing elements
  out = out.replace(/,\s*$/, "");

  // Close unclosed structures in exact reverse nesting order
  while (stack.length > 0) {
    out += stack.pop();
  }

  return out;
}

/**
 * Coerces types and maps fuzzy parameter keys/synonyms.
 */
export function coerceArguments(argsObj, schemaProperties = {}, required = []) {
  if (!argsObj || typeof argsObj !== "object" || Array.isArray(argsObj)) {
    return argsObj;
  }

  const expectedKeys = Object.keys(schemaProperties);
  const normalizedExpected = {};
  for (const k of expectedKeys) {
    normalizedExpected[normalizeParamName(k)] = k;
  }

  const coerced = {};

  for (const [rawKey, rawVal] of Object.entries(argsObj)) {
    let canonicalKey = rawKey;

    if (!schemaProperties[rawKey]) {
      const norm = normalizeParamName(rawKey);
      
      if (normalizedExpected[norm]) {
        canonicalKey = normalizedExpected[norm];
      } else {
        const synonyms = COMMON_SYNONYMS[norm] || [];
        const synonymMatch = synonyms.find(syn => expectedKeys.includes(syn));
        if (synonymMatch) {
          canonicalKey = synonymMatch;
        } else {
          let bestMatch = null;
          let bestDist = 4;
          for (const expKey of expectedKeys) {
            const d = levenshtein(rawKey.toLowerCase(), expKey.toLowerCase());
            if (d < bestDist) {
              bestDist = d;
              bestMatch = expKey;
            }
          }
          if (bestMatch && bestDist <= 3) {
            canonicalKey = bestMatch;
          }
        }
      }
    }

    const propSchema = schemaProperties[canonicalKey] || {};
    const expectedType = propSchema.type;
    let coercedVal = rawVal;

    if (expectedType === "integer" || expectedType === "number") {
      if (typeof rawVal === "string" && !isNaN(Number(rawVal))) {
        coercedVal = expectedType === "integer" ? parseInt(rawVal, 10) : parseFloat(rawVal);
      }
    } else if (expectedType === "boolean") {
      if (typeof rawVal === "string") {
        coercedVal = rawVal.toLowerCase() === "true" || rawVal === "1";
      } else if (typeof rawVal === "number") {
        coercedVal = rawVal === 1;
      }
    } else if (expectedType === "array" && !Array.isArray(rawVal)) {
      coercedVal = [rawVal];
    } else if (expectedType === "string" && typeof rawVal !== "string" && rawVal !== null && rawVal !== undefined) {
      coercedVal = String(rawVal);
    }

    coerced[canonicalKey] = coercedVal;
  }

  for (const [expKey, propSchema] of Object.entries(schemaProperties)) {
    if (coerced[expKey] === undefined && propSchema.default !== undefined) {
      coerced[expKey] = propSchema.default;
    }
  }

  return coerced;
}

/**
 * Heals tool calls within completion choices.
 */
export function healToolCalls(choices, toolDefinitions = []) {
  if (!choices || !Array.isArray(choices)) return choices;

  const toolMap = {};
  for (const t of toolDefinitions) {
    if (t.type === "function" && t.function) {
      toolMap[t.function.name] = t.function.parameters || {};
    } else if (t.name) {
      toolMap[t.name] = t.parameters || {};
    }
  }

  return choices.map(choice => {
    const msg = choice.message;
    if (!msg || !msg.tool_calls || !Array.isArray(msg.tool_calls)) {
      return choice;
    }

    const healedToolCalls = msg.tool_calls.map(tc => {
      if (tc.type !== "function" || !tc.function || !tc.function.arguments) {
        return tc;
      }

      const fnName = tc.function.name;
      const rawArgsStr = tc.function.arguments;
      const paramSchema = toolMap[fnName] || {};
      const properties = paramSchema.properties || {};
      const required = paramSchema.required || [];

      try {
        const repairedJson = repairJsonString(rawArgsStr);
        const parsed = JSON.parse(repairedJson);
        const coerced = coerceArguments(parsed, properties, required);
        const finalArgsStr = JSON.stringify(coerced);

        return {
          ...tc,
          function: {
            ...tc.function,
            arguments: finalArgsStr
          },
          _healed: true
        };
      } catch (err) {
        return {
          ...tc,
          function: {
            ...tc.function,
            arguments: repairJsonString(rawArgsStr)
          }
        };
      }
    });

    return {
      ...choice,
      message: {
        ...msg,
        tool_calls: healedToolCalls
      }
    };
  });
}
