#!/usr/bin/env node
// Small real-model acceptance set; no application data or mutations.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
const output = process.argv[2];
const base = process.env.REVIEW_LIVE_LLM_URL;
if (!output || !base)
  throw Error("Provide an artifact directory and REVIEW_LIVE_LLM_URL.");
const headers = {
  "Content-Type": "application/json",
  ...(process.env.REVIEW_LIVE_LLM_KEY
    ? { Authorization: `Bearer ${process.env.REVIEW_LIVE_LLM_KEY}` }
    : {}),
};
const source = readFileSync(
  "backend/src/main/java/com/eonmux/chatllm/agents/SceneDecisionAgent.java",
  "utf8",
);
const prompt = (name) => {
  const result = source.match(
    new RegExp(`${name}_PROMPT = """([\\s\\S]*?)""";`),
  );
  if (!result) throw Error("Missing production prompt " + name);
  return result[1].replace(/^        /gm, "").trim();
};
const models = await (
  await fetch(base.replace(/\/$/, "") + "/v1/models", {
    headers,
    signal: AbortSignal.timeout(10_000),
  })
).json();
const model = process.env.REVIEW_LIVE_LLM_MODEL || models.data?.[0]?.id;
if (!model) throw Error("No live model available");
const stage =
  "STAGE: Current location Lantern Quay. Current area Market Square. Connected adjacent area: Lantern Inn (inn), through Follow the lanterns. The innkeeper is friendly and no obstacle exists.";
const cases = [
  {
    question: "TRAVEL",
    action: "Let's go to the Lantern Inn now.",
    check: (r) => r.move === true && r.currentArea === "Lantern Inn",
  },
  {
    question: "TRAVEL",
    action: "Let's not go to the inn yet. I want to stay in the square.",
    check: (r) => r.move === false,
  },
  {
    question: "ENCOUNTER",
    action: "I wave to the friendly innkeeper and ask for a room.",
    check: (r) => r.encounter === false,
  },
  {
    question: "INTERACTION",
    action:
      "I open the wooden chest beside the counter to examine its contents.",
    check: (r) => r.occurred === true && typeof r.result === "string",
  },
];
const report = { model, checks: [] };
let failed = false;
for (const entry of cases) {
  const started = Date.now();
  const response = await fetch(
    base.replace(/\/$/, "") + "/v1/chat/completions",
    {
      method: "POST",
      headers,
      signal: AbortSignal.timeout(60_000),
      body: JSON.stringify({
        model,
        temperature: 0,
        max_tokens: 500,
        chat_template_kwargs: { enable_thinking: false },
        messages: [
          { role: "system", content: prompt(entry.question) },
          {
            role: "user",
            content:
              stage +
              "\nLatest PLAYER ACTION: " +
              entry.action +
              '\nCompleted character reply: "All right, I am with you."',
          },
        ],
      }),
    },
  );
  const data = await response.json();
  const text = data.choices?.[0]?.message?.content || "";
  let result;
  let error;
  try {
    result = JSON.parse(text);
    if (!entry.check(result)) throw Error("Unexpected decision");
  } catch (e) {
    error = String(e);
    failed = true;
  }
  report.checks.push({
    question: entry.question,
    action: entry.action,
    text,
    result,
    error,
    passed: !error,
    milliseconds: Date.now() - started,
  });
}
writeFileSync(
  join(output, "live-judgments.json"),
  JSON.stringify(report, null, 2),
);
console.log(
  `${report.checks.filter((c) => c.passed).length}/${cases.length} real-model judgment checks passed (${model}).`,
);
if (failed) process.exitCode = 1;
