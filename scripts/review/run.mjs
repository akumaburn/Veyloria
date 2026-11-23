#!/usr/bin/env node
import { spawn, spawnSync } from "node:child_process";
import {
  createWriteStream,
  mkdirSync,
  writeFileSync,
  readdirSync,
  existsSync,
} from "node:fs";
import { resolve, join } from "node:path";
import net from "node:net";
import { fileURLToPath } from "node:url";
import { startProvider } from "./provider.mjs";
import { seed } from "./seed.mjs";
import { report } from "./report.mjs";

const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const flags = new Set(process.argv.slice(2));
if (flags.has("--help")) {
  console.log(
    "Usage: node scripts/review/run.mjs [--browser-only] [--live-tts] [--demo]\nSee docs/automated-review.md for prerequisites and live LLM environment options.",
  );
  process.exit(0);
}
for (const flag of flags)
  if (!["--browser-only", "--live-tts", "--demo"].includes(flag))
    throw Error("Unknown review option: " + flag);
if (
  flags.has("--demo") &&
  (!process.env.REVIEW_LIVE_LLM_URL || !flags.has("--live-tts"))
)
  throw Error(
    "A product demo requires REVIEW_LIVE_LLM_URL and --live-tts so the film uses actual model output and speech.",
  );
const runId = new Date().toISOString().replaceAll(/[:.]/g, "-");
const output = join(root, ".runtime/review", runId);
mkdirSync(output, { recursive: true });
const children = [];
const results = [];
const python =
  process.env.REVIEW_PYTHON || join(root, ".runtime/tts/venv/bin/python");
let provider;
let composeStarted = false;
const port = () =>
  new Promise((yes, no) => {
    const s = net.createServer();
    s.on("error", no);
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => yes(p));
    });
  });
const ports = Object.fromEntries(
  await Promise.all(
    ["db", "redis", "api", "web", "model", "speech"].map(async (k) => [
      k,
      await port(),
    ]),
  ),
);
const env = {
  ...process.env,
  REVIEW_DB_PORT: String(ports.db),
  REVIEW_REDIS_PORT: String(ports.redis),
};
const project = `veyloria-review-${process.pid}`;
const compose = [
  "compose",
  "--project-name",
  project,
  "--env-file",
  "/dev/null",
  "-f",
  join(root, "scripts/review/compose.yml"),
];
const manifest = {
  reviewTheme: process.env.REVIEW_THEME || "dark",
  sourceCommit: spawnSync("git", ["rev-parse", "HEAD"], {
    cwd: root,
    encoding: "utf8",
  }).stdout.trim(),
  sourceDirty: Boolean(
    spawnSync("git", ["status", "--porcelain"], {
      cwd: root,
      encoding: "utf8",
    }).stdout.trim(),
  ),
  runId,
  output,
  project,
  ports,
  baseURL: `http://127.0.0.1:${ports.web}`,
  apiURL: `http://127.0.0.1:${ports.api}/graphql`,
  boundary: flags.has("--demo")
    ? "actual external LLM and Ming speech; disposable backend, database, Redis and browser"
    : "scripted LLM and synthetic CPU speech; real backend, database, Redis and browser",
  results,
};
function save() {
  writeFileSync(join(output, "run.json"), JSON.stringify(manifest, null, 2));
  writeFileSync(
    join(root, ".runtime/review/latest.json"),
    JSON.stringify({ output }, null, 2),
  );
}
function child(command, args, options = {}) {
  const log = createWriteStream(join(output, options.log || "process.log"), {
    flags: "a",
  });
  const baseEnv = options.isolated
    ? Object.fromEntries(
        ["PATH", "HOME", "JAVA_HOME", "LANG", "TMPDIR", "LD_LIBRARY_PATH"]
          .filter((k) => process.env[k])
          .map((k) => [k, process.env[k]]),
      )
    : env;
  const p = spawn(command, args, {
    cwd: options.cwd || root,
    env: { ...baseEnv, ...options.env },
    stdio: ["pipe", "pipe", "pipe"],
    detached: true,
  });
  p.stdout.pipe(log, { end: false });
  p.stderr.pipe(log, { end: false });
  p.stdin.on("error", () => {}); // Failed children are reported through their exit/error promise.
  p.on("error", (e) => log.write(String(e)));
  p.once("close", () => log.end());
  children.push(p);
  return p;
}
async function command(label, exe, args, options = {}) {
  console.log(label);
  const start = Date.now();
  const p = child(exe, args, {
    log: label.replaceAll(/\W/g, "-") + ".log",
    ...options,
  });
  if (options.input !== undefined) p.stdin.end(options.input);
  else p.stdin.end();
  let timedOut = false;
  const deadline = setTimeout(
    () => {
      timedOut = true;
      try {
        process.kill(-p.pid, "SIGTERM");
      } catch {}
    },
    options.timeoutMs || 15 * 60_000,
  );
  let code;
  try {
    code = await new Promise((yes, no) => {
      p.once("error", no);
      p.once("exit", yes);
    });
  } finally {
    clearTimeout(deadline);
  }
  results.push({
    label,
    code,
    timedOut,
    signal: p.signalCode,
    seconds: Math.round((Date.now() - start) / 1000),
  });
  save();
  if (code !== 0)
    throw Error(`${label} failed (${p.signalCode || code}); see ${output}`);
}
async function ready(url, p) {
  for (let n = 0; n < 150; n++) {
    if (p?.exitCode !== null && p?.exitCode !== undefined)
      throw Error(`Service exited before ready: ${url}`);
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(1500) });
      if (r.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw Error(`Readiness deadline: ${url}`);
}
async function graphql(query, variables = {}, token) {
  const r = await fetch(manifest.apiURL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ query, variables }),
  });
  const b = await r.json();
  if (b.errors?.length) throw Error(JSON.stringify(b.errors));
  return b.data;
}
let cleanupPromise;
function cleanup() {
  cleanupPromise ??= (async () => {
    for (const p of children.reverse()) {
      if (p.exitCode === null) {
        try {
          process.kill(-p.pid, "SIGTERM");
        } catch {}
      }
    }
    await new Promise((r) => setTimeout(r, 1500));
    for (const p of children) {
      if (p.exitCode === null) {
        try {
          process.kill(-p.pid, "SIGKILL");
        } catch {}
      }
    }
    if (provider) provider.close();
    if (composeStarted)
      await new Promise((done) => {
        const p = spawn(
          "docker",
          [...compose, "down", "--volumes", "--remove-orphans"],
          { env, stdio: "ignore" },
        );
        p.once("exit", done);
        p.once("error", done);
      });
  })();
  return cleanupPromise;
}

let stopping = false;
for (const signal of ["SIGINT", "SIGTERM"])
  process.once(signal, async () => {
    if (stopping) return;
    stopping = true;
    await cleanup();
    process.exit(130);
  });
try {
  save();
  console.log(`Review artifacts: ${output}`);
  if (!process.argv.includes("--browser-only")) {
    await command("Backend tests", "./gradlew", ["test", "--console=plain"], {
      cwd: join(root, "backend"),
      env: { RUN_INVENTORY_DB_TESTS: "true" },
    });
    await command(
      "Frontend tests",
      "node",
      ["node_modules/vitest/vitest.mjs", "run", "--coverage"],
      { cwd: join(root, "frontend"), env: { NODE_ENV: "test" } },
    );
    await command(
      "Frontend typecheck",
      "node",
      ["node_modules/typescript/bin/tsc", "--noEmit"],
      { cwd: join(root, "frontend") },
    );
    await command("Frontend lint", "bun", ["run", "lint"], {
      cwd: join(root, "frontend"),
    });
    await command("Frontend package", "bun", ["run", "build"], {
      cwd: join(root, "frontend"),
    });
    await command(
      "Speech tests",
      python,
      ["-m", "pytest", "services/tts/tests", "-q"],
      { env: { PYTHONPATH: join(root, "services/tts") } },
    );
  }
  await command(
    "Browser harness typecheck",
    "node",
    ["node_modules/typescript/bin/tsc", "--noEmit", "-p", "tsconfig.e2e.json"],
    { cwd: join(root, "frontend") },
  );
  await command(
    "Backend package",
    "./gradlew",
    [
      "build",
      "-x",
      "test",
      "--console=plain",
      "-Dquarkus.package.jar.type=uber-jar",
    ],
    { cwd: join(root, "backend") },
  );
  composeStarted = true;
  await command("Disposable data services", "docker", [
    ...compose,
    "up",
    "--detach",
    "--wait",
    "--wait-timeout",
    "150",
  ]);
  provider = await startProvider(
    ports.model,
    join(output, "model-calls.jsonl"),
  );
  const jar = readdirSync(join(root, "backend/build")).find((f) =>
    f.endsWith("-runner.jar"),
  );
  if (!jar) throw Error("Packaged backend runner is missing");
  const api = child(
    "java",
    ["-Xmx768m", "-jar", join(root, "backend/build", jar)],
    {
      cwd: output,
      log: "backend.log",
      isolated: true,
      env: {
        DATABASE_HOST: "127.0.0.1",
        DATABASE_PORT: String(ports.db),
        DATABASE_NAME: "veyloria_review",
        DATABASE_USER: "review",
        DATABASE_PASSWORD: "review-local-only",
        REDIS_HOST: "127.0.0.1",
        REDIS_PORT: String(ports.redis),
        REDIS_PASSWORD: "",
        REDIS_DATABASE: "0",
        JWT_SECRET: "isolated-review-only-secret-at-least-48-characters",
        DEVELOPMENT_MODE: "false",
        ENVIRONMENT: "review",
        DEBUG_MODE: "false",
        QUARKUS_HTTP_HOST: "127.0.0.1",
        QUARKUS_HTTP_PORT: String(ports.api),
        ALLOWED_ORIGINS: manifest.baseURL,
        RATE_LIMIT_PER_MINUTE: "10000",
        LLM_ENABLED: "true",
        LLM_EXTERNAL_SERVER: "true",
        LLM_EXTERNAL_BASE_URL: flags.has("--demo")
          ? process.env.REVIEW_LIVE_LLM_URL
          : `http://127.0.0.1:${ports.model}`,
        LLM_EXTERNAL_MODEL: flags.has("--demo")
          ? process.env.REVIEW_LIVE_LLM_MODEL || "auto"
          : "review-fixture-model",
        LLM_EXTERNAL_API_KEY: "",
        SHOW_GRAPHQL_ERROR_DETAILS: "true",
      },
    },
  );
  await ready(`http://127.0.0.1:${ports.api}/health/ready`, api);
  const fixture = await seed({
    graphql,
    demo: flags.has("--demo"),
    sql: (input) =>
      command(
        "Seed disposable fixture",
        "docker",
        [
          ...compose,
          "exec",
          "-T",
          "mysql",
          "mysql",
          "-ureview",
          "-preview-local-only",
          "veyloria_review",
        ],
        { input },
      ),
  });
  writeFileSync(
    join(output, "fixture.json"),
    JSON.stringify(fixture, null, 2),
    { mode: 0o600 },
  );
  const speech = child(
    python,
    [
      "-m",
      "uvicorn",
      flags.has("--demo") ? "app:app" : "speech:app",
      "--host",
      "127.0.0.1",
      "--port",
      String(ports.speech),
      "--workers",
      "1",
    ],
    {
      log: "speech.log",
      env: {
        PYTHONPATH:
          join(root, "scripts/review") +
          ":" +
          join(root, "services/tts") +
          ":" +
          join(root, ".runtime/tts/ming"),
        MING_VOICE_CACHE_DIR: join(
          output,
          flags.has("--demo") ? "demo-voice-references" : "tone-references",
        ),
        HF_HOME: join(root, ".runtime/tts/huggingface"),
        MING_DEVICE: process.env.MING_DEVICE || "cuda:0",
        TTS_ALLOWED_ORIGINS: manifest.baseURL,
      },
    },
  );
  await ready(`http://127.0.0.1:${ports.speech}/health`, speech);
  const web = child(
    "node",
    [
      "node_modules/vite/bin/vite.js",
      "--host",
      "127.0.0.1",
      "--port",
      String(ports.web),
      "--strictPort",
    ],
    {
      cwd: join(root, "frontend"),
      log: "frontend.log",
      isolated: true,
      env: {
        VITE_API_URL: "/graphql",
        VITE_GRAPHQL_ENDPOINT: "/graphql",
        VITE_WS_URL: manifest.baseURL.replace("http:", "ws:") + "/graphql",
        VITE_WS_ENDPOINT: manifest.baseURL.replace("http:", "ws:") + "/graphql",
        BACKEND_DEV_URL: `http://127.0.0.1:${ports.api}`,
        TTS_DEV_URL: `http://127.0.0.1:${ports.speech}`,
      },
    },
  );
  await ready(manifest.baseURL, web);
  await command(
    "Live sandbox GraphQL contract",
    "node",
    ["--experimental-strip-types", "scripts/check-graphql.ts"],
    {
      cwd: join(root, "frontend"),
      env: { GRAPHQL_SCHEMA_URL: manifest.apiURL },
    },
  );
  if (flags.has("--demo"))
    await command("Prepare demo voices", python, [
      "scripts/review/prepare-demo-voices.py",
      output,
    ]);
  await command(
    "Browser review",
    "node",
    [
      "node_modules/@playwright/test/cli.js",
      "test",
      "--config",
      "playwright.config.ts",
    ],
    {
      cwd: join(root, "frontend"),
      env: {
        REVIEW_DIR: output,
        REVIEW_DEMO: flags.has("--demo") ? "1" : "0",
        REVIEW_BASE_URL: manifest.baseURL,
        REVIEW_API_URL: manifest.apiURL,
        PLAYWRIGHT_CHROMIUM_EXECUTABLE:
          process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
          (existsSync("/usr/bin/brave") ? "/usr/bin/brave" : ""),
      },
    },
  );
  if (process.env.REVIEW_LIVE_LLM_URL)
    await command("Real LLM judgments", "node", [
      "scripts/review/live-judgments.mjs",
      output,
    ]);
  if (process.argv.includes("--live-tts") && !flags.has("--demo"))
    await command(
      "Real Ming acceptance",
      python,
      ["scripts/review/live-speech.py", output],
      {
        env: {
          PYTHONPATH:
            join(root, "services/tts") + ":" + join(root, ".runtime/tts/ming"),
          HF_HOME: join(root, ".runtime/tts/huggingface"),
          MING_DEVICE: process.env.MING_DEVICE || "cuda:0",
        },
      },
    );
  if (flags.has("--demo") && process.env.REVIEW_DEMO_CREATION_FROM)
    await command("Compose verified creation chapters", "node", [
      "scripts/review/combine-demo.mjs",
      output,
      process.env.REVIEW_DEMO_CREATION_FROM,
      process.env.REVIEW_DEMO_PREFIX_THROUGH || "map-generated",
    ]);
  await command("Capability short", "node", [
    flags.has("--demo")
      ? "scripts/review/render-demo.mjs"
      : "scripts/review/render-short.mjs",
    output,
  ]);
  manifest.status = "passed";
  save();
  console.log(`Review passed. Evidence and animated short: ${output}`);
} catch (error) {
  manifest.status = "failed";
  manifest.error = String(error);
  save();
  console.error(error);
  process.exitCode = 1;
} finally {
  try {
    report(root, output, manifest);
  } finally {
    await cleanup();
  }
}
