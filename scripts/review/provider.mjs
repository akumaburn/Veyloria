// Deliberately scripted external-model boundary. Never a claim about model quality.
import http from "node:http";
import { appendFileSync } from "node:fs";
export function startProvider(port, logFile) {
  const server = http.createServer(async (req, res) => {
    if (req.method === "GET") {
      res.setHeader("Content-Type", "application/json");
      res.end(
        JSON.stringify({
          data: [{ id: "review-fixture-model", object: "model" }],
          status: "ok",
        }),
      );
      return;
    }
    let raw = "";
    for await (const chunk of req) raw += chunk;
    try {
      const body = JSON.parse(raw);
      const system = (body.messages || [])
        .filter((m) => m.role === "system")
        .map((m) => m.content)
        .join("\n");
      const all = (body.messages || []).map((m) => m.content).join("\n");
      let kind = "dialogue";
      let answer =
        '*I tuck a stray strand behind my ear.* "The inn is just across the square. Let us follow the lanterns."';
      if (system.includes("starting location for a roleplay")) {
        kind = "location-name";
        answer =
          '{"name":"Lantern Quay","description":"A lantern-lit coastal town."}';
      } else if (system.includes("Extract ONLY ONE grounded memory")) {
        kind = "memory";
        answer = '{"entry":null}';
      } else if (system.includes("You are the Skeptic")) {
        kind = "review";
        answer = '{"approved":true,"reason":"Consistent with the stage."}';
      } else if (system.includes("Resolve ONLY travel")) {
        kind = "travel";
        answer = JSON.stringify(
          /go to the lantern inn/i.test(all)
            ? { move: true, currentArea: "Lantern Inn" }
            : { move: false },
        );
      } else if (system.includes("Judge ONLY whether an immediate hostile")) {
        kind = "encounter";
        answer = '{"encounter":false}';
      } else if (system.includes("Resolve ONLY whether this completed round")) {
        kind = "interaction";
        answer = '{"occurred":false}';
      } else if (system.includes('"change":null')) {
        kind = "bookkeeping";
        answer = '{"change":null}';
      } else if (system.includes("You adjudicate ONLY")) {
        kind = "battle-adjudication";
        answer = JSON.stringify({
          outcome: "ONGOING",
          battleState: {
            enemyHp: all.includes("Chosen Action:")
              ? 12
              : Number(all.match(/Enemy HP: ([\d.]+)/)?.[1] || 20),
            partyStatus: "The party holds the bridge.",
            roundOutcome: "The sentinel held the crossing.",
          },
          statChanges: [],
          inventoryChanges: [],
        });
      } else if (system.includes("Generate ONLY the next options")) {
        kind = "battle-options";
        answer =
          '{"actions":[{"title":"Press the advantage","description":"Attempt a coordinated strike.","type":"attack"},{"title":"Withdraw together","description":"Attempt to disengage toward the quay.","type":"retreat"}]}';
      } else if (system.includes("third-person, past-tense combat")) {
        kind = "battle-narration";
        answer =
          "Mira caught the sentinel off balance as Torren covered the crossing. Sparks scattered over the bridge; the party held its ground.";
      } else if (
        system.startsWith("Write only this local resident's brief greeting")
      ) {
        kind = "resident-greeting";
        answer =
          '"Welcome to the Lantern Inn. There is a warm table by the fire, if you would like to rest."';
      } else if (/skeptic|review.*narrat|consistency.*review/i.test(system)) {
        kind = "review";
        answer =
          '{"verdict":"APPROVED","reason":"Consistent with the confirmed stage."}';
      } else if (/you are the narrator/i.test(system)) {
        kind = "narration";
        answer =
          "The party crossed the market square and reached the Lantern Inn. Warm lamplight spilled across the threshold, and the innkeeper looked up from the counter.";
      }
      appendFileSync(
        logFile,
        JSON.stringify({
          kind,
          system,
          prompt: all,
          answer,
          stream: !!body.stream,
        }) + "\n",
      );
      if (body.stream) {
        res.writeHead(200, {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
        });
        for (const content of answer.match(/.{1,32}/gs) || [])
          res.write(
            "data: " +
              JSON.stringify({
                id: "review",
                choices: [
                  { index: 0, delta: { content }, finish_reason: null },
                ],
              }) +
              "\n\n",
          );
        res.end(
          "data: " +
            JSON.stringify({
              choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
              usage: { prompt_tokens: 100, completion_tokens: 40 },
            }) +
            "\n\ndata: [DONE]\n\n",
        );
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            id: "review",
            model: "review-fixture-model",
            choices: [
              {
                message: { role: "assistant", content: answer },
                finish_reason: "stop",
              },
            ],
            usage: { prompt_tokens: 100, completion_tokens: 40 },
          }),
        );
      }
    } catch (error) {
      res.writeHead(500);
      res.end(String(error));
    }
  });
  return new Promise((resolve) =>
    server.listen(port, "127.0.0.1", () => resolve(server)),
  );
}
