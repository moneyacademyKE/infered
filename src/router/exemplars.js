/**
 * Dynamic Tool Exemplar Synthesizer & Continuous Learning Store
 * Dynamically synthesizes in-context tool call exemplars for budget models
 * and tracks tool failure signatures to improve reliability over time.
 */

export function createExemplarStore() {
  return {
    toolStats: {}, // toolName -> { calls: 0, repairs: 0, failures: 0 }
    modelToolStats: {} // "modelId::toolName" -> stats
  };
}

/**
 * Records tool call execution outcome and auto-healing repairs.
 */
export function recordToolOutcome(store, toolName, modelId, success, wasHealed = false) {
  if (!toolName) return;

  if (!store.toolStats[toolName]) {
    store.toolStats[toolName] = { calls: 0, repairs: 0, failures: 0 };
  }
  const ts = store.toolStats[toolName];
  ts.calls += 1;
  if (wasHealed) ts.repairs += 1;
  if (!success) ts.failures += 1;

  const mKey = `${modelId || "default"}::${toolName}`;
  if (!store.modelToolStats[mKey]) {
    store.modelToolStats[mKey] = { calls: 0, repairs: 0, failures: 0 };
  }
  const ms = store.modelToolStats[mKey];
  ms.calls += 1;
  if (wasHealed) ms.repairs += 1;
  if (!success) ms.failures += 1;
}

/**
 * Generates an in-context 1-shot example demonstrating exact JSON schema formatting
 * for a given tool definition.
 */
export function generateToolExemplar(toolDef) {
  const fn = toolDef.type === "function" ? toolDef.function : toolDef;
  if (!fn || !fn.name) return null;

  const props = fn.parameters?.properties || {};
  const sampleArgs = {};

  for (const [key, schema] of Object.entries(props)) {
    if (schema.type === "string") {
      sampleArgs[key] = schema.example || (schema.enum ? schema.enum[0] : `sample_${key}`);
    } else if (schema.type === "integer" || schema.type === "number") {
      sampleArgs[key] = schema.example || (schema.default !== undefined ? schema.default : 10);
    } else if (schema.type === "boolean") {
      sampleArgs[key] = schema.default !== undefined ? schema.default : true;
    } else if (schema.type === "array") {
      sampleArgs[key] = schema.example || [];
    } else if (schema.type === "object") {
      sampleArgs[key] = {};
    }
  }

  return {
    role: "assistant",
    tool_calls: [
      {
        id: `call_example_${fn.name}`,
        type: "function",
        function: {
          name: fn.name,
          arguments: JSON.stringify(sampleArgs)
        }
      }
    ]
  };
}

/**
 * Enriches request messages with dynamic few-shot exemplars when routing complex tools to budget models.
 */
export function enrichPromptWithToolExemplars(requestBody, targetModelId) {
  if (!requestBody.tools || !Array.isArray(requestBody.tools) || requestBody.tools.length === 0) {
    return requestBody;
  }

  // Only inject exemplars for budget/fast models prone to schema errors
  const isBudgetModel = targetModelId.includes("flash") ||
                        targetModelId.includes("mini") ||
                        targetModelId.includes("8b") ||
                        targetModelId.includes("7b");

  if (!isBudgetModel) {
    return requestBody;
  }

  const exemplars = [];
  for (const t of requestBody.tools.slice(0, 2)) {
    const ex = generateToolExemplar(t);
    if (ex) exemplars.push(ex);
  }

  if (exemplars.length === 0) {
    return requestBody;
  }

  // Prepend guideline to system message or messages array
  const guideText = "[TOOL CALL FORMATTING GUIDELINES]: Output all tool call arguments as valid RFC 8259 JSON objects. Use double quotes for all keys. Do not include markdown code fences.";
  
  const existingMessages = [...requestBody.messages];
  const hasSystem = existingMessages.length > 0 && existingMessages[0].role === "system";

  if (hasSystem) {
    existingMessages[0] = {
      ...existingMessages[0],
      content: `${existingMessages[0].content}\n\n${guideText}`
    };
  } else {
    existingMessages.unshift({
      role: "system",
      content: guideText
    });
  }

  return {
    ...requestBody,
    messages: existingMessages
  };
}
