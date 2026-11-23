import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
export function report(root, output, manifest) {
  const json = (p) => JSON.parse(readFileSync(p, "utf8"));
  const coverageFile = join(root, "frontend/coverage/coverage-final.json");
  if (existsSync(coverageFile)) {
    const files = Object.entries(json(coverageFile)).map(([file, value]) => ({
      file: relative(root, file),
      statements: Object.values(value.s).length,
      covered: Object.values(value.s).filter((n) => n > 0).length,
      branches: Object.values(value.b).flat().length,
      coveredBranches: Object.values(value.b)
        .flat()
        .filter((n) => n > 0).length,
    }));
    const totals = files.reduce(
      (sum, f) => ({
        statements: sum.statements + f.statements,
        covered: sum.covered + f.covered,
        branches: sum.branches + f.branches,
        coveredBranches: sum.coveredBranches + f.coveredBranches,
      }),
      { statements: 0, covered: 0, branches: 0, coveredBranches: 0 },
    );
    writeFileSync(
      join(output, "coverage-gaps.json"),
      JSON.stringify(
        {
          source:
            "Vitest application-source instrumentation; browser-only runs reuse the last local unit baseline.",
          totals,
          largestGaps: files
            .sort(
              (a, b) => b.statements - b.covered - (a.statements - a.covered),
            )
            .slice(0, 30),
        },
        null,
        2,
      ),
    );
  }
  const xmlDir = join(root, "backend/build/test-results/test");
  const backend = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  if (existsSync(xmlDir))
    for (const file of readdirSync(xmlDir).filter((f) => f.endsWith(".xml"))) {
      const xml =
        readFileSync(join(xmlDir, file), "utf8").match(
          /<testsuite\b[^>]*>/,
        )?.[0] || "";
      for (const key of Object.keys(backend))
        backend[key] += Number(
          xml.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] || 0,
        );
    }
  const browserPath = join(output, "browser-results.json");
  const browser = existsSync(browserPath) ? json(browserPath).stats : null;
  const review = {
    status: manifest.status,
    backend,
    browser,
    stages: manifest.results,
    manualReviewRequired: [
      "Open desktop/mobile/theme captures and inspect actual rendering.",
      "Listen to real-model WAV files; successful PCM alone does not establish voice quality.",
    ],
    scope:
      "Deterministic fixture journeys plus optional real-model acceptance. Exhaustive path coverage is not claimed.",
  };
  writeFileSync(
    join(output, "review-summary.json"),
    JSON.stringify(review, null, 2),
  );
  writeFileSync(
    join(output, "review-summary.md"),
    `# Product review\n\nStatus: ${manifest.status}.\n\nBackend reports: ${backend.tests} tests, ${backend.failures} failures, ${backend.errors} errors, ${backend.skipped} skipped. Browser results: ${browser ? `${browser.expected} passed; ${browser.unexpected} failed` : "not completed"}.\n\n${manifest.results.map((r) => `- ${r.label}: exit ${r.code} (${r.seconds}s)`).join("\n")}\n\nInspect screenshots and audio before accepting visual or perceptual quality. See coverage-gaps.json for untested frontend statements; a passing workflow does not certify every possible path.\n`,
  );
}
