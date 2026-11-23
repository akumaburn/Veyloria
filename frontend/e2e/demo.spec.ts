import { test, expect, type Locator } from '@playwright/test';
import { creationTour, continuityTour, portabilityTour } from './product-tour';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';

const output = process.env.REVIEW_DIR!;
const fixture = JSON.parse(readFileSync(path.join(output, 'fixture.json'), 'utf8'));
const { id, owner, member, destination } = fixture;
const dir = path.join(output, 'demo');
mkdirSync(dir, { recursive: true });

function wav(pcm: Buffer, rate: number) {
  const header = Buffer.alloc(44);
  header.write('RIFF');
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVEfmt ', 8);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

test('record verified product overview chapters', async ({ browser, request }) => {
  const graphql = async (query: string, variables: Record<string, unknown> = {}, actor = owner) => {
    const res = await request.post(process.env.REVIEW_API_URL!, {
      headers: { Authorization: `Bearer ${actor.accessToken}` },
      data: { query, variables },
      timeout: 120_000,
    });
    const body = await res.json();
    expect(body.errors, JSON.stringify(body.errors)).toBeUndefined();
    return body.data;
  };
  const state = async () =>
    (
      await graphql(
        'query($id:String!){worldState(conversationId:$id){currentAreaId currentArea currentLocationId currentLocation}}',
        { id: id.conversation },
      )
    ).worldState;
  const inventory = async () =>
    await graphql(
      'query($id:String!,$c:String!){characterInventory(conversationId:$id,characterId:$c){inventoryId quantity} characterStats(conversationId:$id,characterId:$c){statName statValue}}',
      { id: id.conversation, c: id.mira },
    );
  const context = await browser.newContext({
    baseURL: process.env.REVIEW_BASE_URL,
    viewport: { width: 1920, height: 960 },
    recordVideo: { dir, size: { width: 1920, height: 960 } },
  });
  await context.addInitScript(({ accessToken, refreshToken }) => {
    localStorage.setItem('authToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    localStorage.setItem(
      'tts-storage',
      JSON.stringify({
        state: {
          settings: { enabled: true, autoPlay: false, speed: 1, volume: 1, serverUrl: '/tts' },
        },
        version: 0,
      }),
    );
    const original = AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start = function (when = 0, offset = 0, duration?: number) {
      if (this.buffer) {
        const data = this.buffer.getChannelData(0);
        const pcm = new Int16Array(data.length);
        for (let i = 0; i < data.length; i++)
          pcm[i] = Math.max(-32768, Math.min(32767, Math.round(data[i] * 32768)));
        const bytes = new Uint8Array(pcm.buffer);
        let binary = '';
        for (let i = 0; i < bytes.length; i += 8192)
          binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
        void (window as any).capturePlayback({
          at: Date.now() + Math.max(0, when - this.context.currentTime) * 1000,
          rate: this.buffer.sampleRate,
          pcm: btoa(binary),
          seconds: this.buffer.duration,
          offset,
          duration,
        });
      }
      return original.call(this, when, offset, duration);
    };
    document.addEventListener('DOMContentLoaded', () => {
      const cursor = document.createElement('div');
      cursor.id = 'film-cursor';
      cursor.setAttribute('aria-hidden', 'true');
      cursor.style.cssText =
        'position:fixed;left:-40px;top:-40px;width:18px;height:18px;border:2px solid #fff;border-radius:50%;box-shadow:0 1px 5px #0009;pointer-events:none;z-index:2147483647;transform:translate(-50%,-50%);background:#ffffff22';
      document.body.append(cursor);
      document.addEventListener('pointermove', (e) => {
        cursor.style.left = e.clientX + 'px';
        cursor.style.top = e.clientY + 'px';
      });
    });
  }, owner);
  const pcmMetadata = new Map<string, { speaker: string; text: string; seconds: number }>();
  const audio: Array<{
    at: number;
    seconds: number;
    file: string;
    hash: string;
    speaker: string;
    text: string;
  }> = [];
  await context.exposeBinding('capturePlayback', (_source, payload) => {
    const pcm = Buffer.from(payload.pcm, 'base64'),
      hash = createHash('sha256').update(pcm).digest('hex');
    const metadata = pcmMetadata.get(hash) || { speaker: 'unknown', text: '' };
    const file = `playback-${audio.length}.wav`;
    writeFileSync(path.join(dir, file), wav(pcm, payload.rate));
    audio.push({ at: payload.at, seconds: payload.seconds, file, hash, ...metadata });
  });
  const page = await context.newPage();
  const started = Date.now();
  const now = () => (Date.now() - started) / 1000;
  const syncStart = now();
  await page.setContent(
    '<html><body style="margin:0;background:#00ff00;width:100vw;height:100vh"></body></html>',
  );
  await page.waitForTimeout(700);
  const sync = { start: syncStart, end: now(), color: '#00ff00' };
  const beats: Array<{ name: string; caption: string; start: number; end: number }> = [];
  const evidence: Record<string, unknown> = {};
  const wait = (ms = 900) => page.waitForTimeout(ms);
  const shot = async (name: string, caption: string, action: () => Promise<void>) => {
    const start = now();
    await action();
    await wait(1600);
    beats.push({ name, caption, start, end: now() });
    await page.screenshot({ path: path.join(dir, name + '.png') });
  };
  const click = async (locator: Locator) => {
    await locator.scrollIntoViewIfNeeded();
    const b = await locator.boundingBox();
    if (b) await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 18 });
    await wait(350);
    await locator.click();
    await wait(550);
  };
  const tools = async (tab: string) => {
    if (!(await page.getByTitle(tab, { exact: true }).isVisible()))
      await click(page.getByRole('button', { name: 'RP Tools', exact: true }));
    await click(page.getByTitle(tab, { exact: true }));
  };
  const chat = async () => {
    await page.goto(`/chat/${id.conversation}`);
    await expect(page.getByRole('textbox', { name: 'Message input', exact: true })).toBeVisible();
    await wait(1100);
    await page.mouse.move(820, 350);
    await page.mouse.wheel(0, -2500);
    await wait(700);
  };
  page.on('websocket', (socket) =>
    socket.on('framereceived', (frame) => {
      try {
        const p = JSON.parse(String(frame.payload));
        if (p.type === 'CHUNK' && p.audio_base64)
          pcmMetadata.set(
            createHash('sha256').update(Buffer.from(p.audio_base64, 'base64')).digest('hex'),
            { speaker: p.speaker, text: p.chunk_text, seconds: p.duration },
          );
      } catch {
        /* GraphQL and transport frames are not all speech chunks. */
      }
    }),
  );
  const move = async (text: string, target: string) => {
    await page.getByRole('textbox', { name: 'Message input', exact: true }).fill(text);
    await click(page.getByRole('button', { name: 'Send message', exact: true }));
    await expect.poll(async () => (await state()).currentAreaId, { timeout: 90_000 }).toBe(target);
    await expect
      .poll(
        async () =>
          (
            await graphql('query($id:String!){conversation(id:$id){groupState}}', {
              id: id.conversation,
            })
          ).conversation.groupState,
        { timeout: 90_000 },
      )
      .toBe('WAITING_FOR_USER');
  };
  let recording;
  try {
    if (process.env.REVIEW_DEMO_PHASE === 'postlude') {
      await chat();
      evidence.browserScale = await page.evaluate(() => ({
        viewport: innerWidth,
        scale: visualViewport?.scale,
        devicePixelRatio,
      }));
      await portabilityTour({ page, click, shot, graphql, id, evidence, tools, chat });
      await page.goto('/roleplay');
      await shot(
        'ending',
        'A world to wander. People to remember. A story that becomes yours.',
        async () => {
          await wait(2200);
        },
      );
      recording = page.video();
      return;
    }
    const creationSource = process.env.REVIEW_DEMO_CREATION_FROM;
    if (creationSource) {
      const previous = JSON.parse(
        readFileSync(path.join(creationSource, 'demo/timeline.json'), 'utf8'),
      );
      expect(
        previous.beats.some((beat: { name: string }) => beat.name === 'map-generated'),
      ).toBeTruthy();
      expect(previous.evidence.roster.characterIds).toHaveLength(3);
      evidence.creationSource = creationSource;
    } else await creationTour({ page, click, shot, graphql, id, evidence, tools, chat });
    await chat();
    evidence.browserScale = await page.evaluate(() => ({
      viewport: innerWidth,
      scale: visualViewport?.scale,
      devicePixelRatio,
    }));
    expect((evidence.browserScale as { scale: number }).scale).toBe(1);
    await shot(
      'opening',
      'One conversation. A narrator, your companions, and a shared world.',
      async () => {
        await expect(page.getByText(/Evening settled over Lantern Quay/)).toBeVisible();
        await wait(2000);
      },
    );
    const voiceStart = now();
    await click(page.getByRole('button', { name: 'Read conversation', exact: true }));
    await expect(page.getByText('All loaded replies read', { exact: true })).toBeVisible({
      timeout: 180_000,
    });
    // Chromium privacy features can perturb samples returned by getChannelData.
    // Correlate the played buffer with the adapter chunk by its exact sample duration.
    const unmatched = [...pcmMetadata.values()];
    for (const entry of audio) {
      const index = unmatched.findIndex(
        (chunk) => Math.abs(chunk.seconds - entry.seconds) < 0.00001,
      );
      if (index >= 0) Object.assign(entry, unmatched.splice(index, 1)[0]);
    }
    expect(audio.some((a) => a.speaker === 'narrator')).toBeTruthy();
    expect(audio.some((a) => a.speaker === 'character')).toBeTruthy();
    beats.push({
      name: 'voices',
      caption: 'Listen: the narrator, then Mira Solstice in her own voice.',
      start: voiceStart,
      end: now() + 0.5,
    });
    await wait(700);
    await shot('health-before', 'Mira is injured: 12 / 20 HP.', async () => {
      await tools('Characters');
      await click(page.getByRole('button', { name: /^Relationships/ }).first());
      await expect(page.getByText('12/20', { exact: true }).first()).toBeVisible();
      await wait(1200);
    });
    evidence.inventoryBefore = await inventory();
    await shot('use-item', 'Use one healing draught. The item has a real effect.', async () => {
      await expect(page.getByText('x2', { exact: true })).toBeVisible();
      await wait(900);
      await click(page.getByRole('button', { name: 'Use Healing draught', exact: true }));
      await expect
        .poll(
          async () =>
            (await inventory()).characterStats.find(
              (s: { statName: string }) => s.statName === 'HP',
            ).statValue,
        )
        .toBe(17);
    });
    await shot('health-after', '17 / 20 HP. One draught consumed.', async () => {
      await page.mouse.move(1760, 430);
      await page.mouse.wheel(0, -1000);
      await wait(700);
      await expect(page.getByText('17/20', { exact: true }).first()).toBeInViewport();
      await wait(1500);
    });
    evidence.inventoryAfter = await inventory();
    expect(
      (evidence.inventoryAfter as Awaited<ReturnType<typeof inventory>>).characterInventory.find(
        (item: { inventoryId: string }) => item.inventoryId === id.potion,
      ).quantity,
    ).toBe(1);
    await page.getByRole('button', { name: 'RP Tools', exact: true }).click();
    await page.goto(`/map/${id.conversation}`);
    await expect(page.getByText('9 of 9 areas explored', { exact: false })).toBeVisible();
    await shot(
      'local-before',
      'An explored town: real rooms, passages and the current position.',
      async () => {
        await wait(2200);
      },
    );
    await click(page.getByRole('link', { name: 'Back to conversation', exact: true }));
    await expect(page.getByRole('textbox', { name: 'Message input' })).toBeVisible();
    await shot('choose-inn', '“Let us go to the Lantern Inn.”', async () => {
      await move('Let us go to the Lantern Inn now.', id.inn);
    });
    await shot(
      'arrival',
      'Confirmed travel, narrator arrival, then the innkeeper’s greeting.',
      async () => {
        await expect
          .poll(
            async () => {
              const r = await graphql(
                'query($id:String!){messages(conversationId:$id){items{messageType characterId content}}}',
                { id: id.conversation },
              );
              return r.messages.items.some(
                (m: { characterId: string }) =>
                  m.characterId && ![id.mira, id.torren].includes(m.characterId),
              );
            },
            { timeout: 45_000 },
          )
          .toBeTruthy();
        const rows = (
          await graphql(
            'query($id:String!){messages(conversationId:$id){items{content messageType characterId}}}',
            { id: id.conversation },
          )
        ).messages.items;
        const narration = rows.find(
          (m: { messageType: string }) => m.messageType.toUpperCase() === 'NARRATION',
        );
        if (narration)
          await page
            .getByText(narration.content.slice(0, 60), { exact: false })
            .first()
            .evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }));
        await wait(1200);
      },
    );
    await shot('innkeeper', 'The local innkeeper greets the arriving party.', async () => {
      const rows = (
        await graphql(
          'query($id:String!){messages(conversationId:$id){items{content characterId}}}',
          { id: id.conversation },
        )
      ).messages.items;
      const greeting = rows.find(
        (m: { characterId: string }) =>
          m.characterId && ![id.mira, id.torren].includes(m.characterId),
      );
      if (greeting)
        await page
          .getByText(greeting.content.slice(0, 60), { exact: false })
          .first()
          .evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }));
      await wait(1000);
    });
    evidence.localArrival = await state();
    await page.goto(`/map/${id.conversation}`);
    await expect(page.getByText('Lantern Inn', { exact: true }).first()).toBeVisible();
    await shot(
      'local-after',
      'The current-room marker now follows the story to the inn.',
      async () => {
        await wait(2200);
      },
    );
    // Real intervening actions are recorded, but omitted from the short with an elapsed-time caption.
    await chat();
    await move('I leave the Lantern Inn and return to Market Square.', id.square);
    await move('I walk from Market Square to Harbor Gate.', id.gate);
    await page.goto(`/map/${id.conversation}`);
    await click(page.getByRole('button', { name: 'Global', exact: true }));
    await click(page.getByRole('button', { name: '2D View', exact: true }));
    await expect(page.locator('canvas').first()).toBeVisible();
    await click(page.getByRole('button', { name: 'Fit all', exact: true }));
    evidence.cultures = (
      await graphql('query($id:String!){culturalRegions(mapId:$id){culture name}}', { id: id.map })
    ).culturalRegions;
    expect((evidence.cultures as unknown[]).length).toBeGreaterThan(1);
    await shot(
      'empires-2d',
      'Empires and cultures give the world its borders and identities.',
      async () => {
        await click(page.getByRole('button', { name: 'Empires', exact: true }));
        await expect(page.getByRole('button', { name: 'Empires', exact: true })).toHaveAttribute(
          'aria-pressed',
          'true',
        );
        await wait(2600);
      },
    );
    await shot(
      'empires-3d',
      'See those territories across the three-dimensional landscape.',
      async () => {
        await click(page.getByRole('button', { name: '3D View', exact: true }));
        await click(page.getByRole('button', { name: 'Show Empires', exact: true }));
        await expect(page.getByRole('button', { name: 'Hide Empires', exact: true })).toBeVisible();
        await wait(3500);
      },
    );
    await click(page.getByRole('button', { name: '2D View', exact: true }));
    await click(page.getByRole('button', { name: 'Fit all', exact: true }));
    const canvas = page.locator('canvas').first();
    const rect = await canvas.boundingBox();
    expect(rect).toBeTruthy();
    await shot(
      'route',
      'Later, at the harbor gate: choose the road to the Old Observatory.',
      async () => {
        const px = rect!.x + ((destination.x + 100) / 200) * rect!.width,
          py = rect!.y + ((destination.y + 100) / 200) * rect!.height;
        await page.mouse.move(px, py, { steps: 24 });
        await wait(500);
        await page.mouse.click(px, py);
        await expect(page.getByRole('button', { name: 'Travel Here', exact: true })).toBeEnabled();
        await wait(1600);
      },
    );
    await shot(
      'journey',
      'Begin narrated travel. The party advances through the world.',
      async () => {
        await click(page.getByRole('button', { name: 'Travel Here', exact: true }));
        await click(page.getByRole('button', { name: '3D View', exact: true }));
        await expect
          .poll(
            async () =>
              (
                await graphql(
                  'query($id:String!){activeJourney(conversationId:$id){currentStep status}}',
                  { id: id.conversation },
                )
              ).activeJourney?.currentStep || 0,
            { timeout: 90_000 },
          )
          .toBeGreaterThan(0);
        const terrain = await page.locator('canvas').last().boundingBox();
        expect(terrain).toBeTruthy();
        // Use the real camera controls: a closer terrain view, independently of editorial UI zoom.
        await page.mouse.move(
          terrain!.x + terrain!.width * 0.5,
          terrain!.y + terrain!.height * 0.54,
        );
        for (let step = 0; step < 4; step++) {
          await page.mouse.wheel(0, -100);
          await wait(180);
        }
        evidence.terrainCamera = { zoomInSteps: 4, control: 'native scroll-to-cursor' };
        evidence.journeyProgress = (
          await graphql(
            'query($id:String!){activeJourney(conversationId:$id){currentStep status}}',
            { id: id.conversation },
          )
        ).activeJourney;
        await wait(4500);
      },
    );
    const handledEncounters: unknown[] = [];
    await expect
      .poll(
        async () => {
          const location = await state();
          if (location.currentLocationId === id.destination) return id.destination;
          const progress = (
            await graphql('query($id:String!){activeJourney(conversationId:$id){status}}', {
              id: id.conversation,
            })
          ).activeJourney;
          if (progress?.status === 'PAUSED') {
            const encounter = (
              await graphql(
                'query($id:String!){activeBattle(conversationId:$id){battleId roundNumber actionOptions}}',
                { id: id.conversation },
              )
            ).activeBattle;
            if (!encounter) {
              const proceed = page.getByRole('button', { name: 'Continue journey', exact: true });
              if ((await proceed.isVisible()) && (await proceed.isEnabled())) {
                await click(proceed);
                handledEncounters.push({ kind: 'noncombat encounter', action: 'Continue journey' });
              }
              return null;
            }
            if (!encounter.actionOptions) return null;
            if (encounter) {
              const choices = JSON.parse(encounter.actionOptions).actions;
              const retreat = choices.findIndex(
                (choice: { type: string }) => choice.type === 'retreat',
              );
              if (retreat < 0)
                throw Error(
                  'Unplanned encounter has no retreat action; capture needs a new story beat.',
                );
              const input = {
                battleId: encounter.battleId,
                roundNumber: encounter.roundNumber,
                optionIndex: retreat,
              };
              for (const actor of [owner, member])
                await graphql(
                  'mutation($input:VoteActionInput!){voteAction(input:$input){id}}',
                  { input },
                  actor,
                );
              const resolved = (
                await graphql(
                  'mutation($b:String!,$r:Int){resolveBattleRound(battleId:$b,roundNumber:$r){status roundNumber currentNarration}}',
                  { b: encounter.battleId, r: encounter.roundNumber },
                )
              ).resolveBattleRound;
              handledEncounters.push({ choice: choices[retreat], result: resolved });
              if (resolved.status === 'ACTIVE') return null;
            }
            await graphql('mutation($id:String!){resumeJourney(conversationId:$id){status}}', {
              id: id.conversation,
            });
          }
          return null;
        },
        { timeout: 300_000, intervals: [1500] },
      )
      .toBe(id.destination);
    evidence.interveningEncounters = handledEncounters;
    await shot(
      'world-arrival',
      'The journey reaches the observatory. The world state follows.',
      async () => {
        await wait(2500);
      },
    );
    evidence.worldArrival = await state();
    const active = await graphql(
      'query($id:String!){activeBattle(conversationId:$id){battleId roundNumber actionOptions}}',
      { id: id.conversation },
    );
    const battle =
      active.activeBattle ||
      (
        await graphql(
          'mutation($input:InitiateBattleInput!){initiateBattle(input:$input){battleId roundNumber actionOptions}}',
          {
            input: {
              conversationId: id.conversation,
              enemyName: 'Bridge Sentinel',
              enemyDescription: 'A clockwork guardian blocks the observatory bridge.',
              enemyHp: 20,
              narrativeContext:
                'The party reaches the observatory approach. The sentinel bars the bridge.',
            },
          },
        )
      ).initiateBattle;
    await chat();
    await tools('Battle');
    const actions = JSON.parse(battle.actionOptions).actions;
    const option = actions.findIndex((a: { type: string }) => a.type === 'attack');
    const selected = option >= 0 ? option : 0;
    await graphql(
      'mutation($input:VoteActionInput!){voteAction(input:$input){id}}',
      {
        input: {
          battleId: battle.battleId,
          roundNumber: battle.roundNumber,
          optionIndex: selected,
        },
      },
      member,
    );
    await shot('vote', 'Two players. A visible vote for the party’s next move.', async () => {
      await click(page.getByRole('button', { name: new RegExp(actions[selected].title) }));
      await expect(page.getByText(/2 votes/).first()).toBeVisible();
      await wait(1400);
    });
    await shot(
      'combat-result',
      'Resolve the chosen action: a new round, narration and consequences.',
      async () => {
        await click(page.getByRole('button', { name: 'Resolve party action', exact: true }));
        await expect
          .poll(
            async () =>
              (
                await graphql('query($b:String!){battle(battleId:$b){roundNumber}}', {
                  b: battle.battleId,
                })
              ).battle.roundNumber,
            { timeout: 90_000 },
          )
          .toBe(battle.roundNumber + 1);
        await page.mouse.move(1760, 430);
        await page.mouse.wheel(0, -2000);
        await wait(3000);
      },
    );
    evidence.battle = await graphql(
      'query($b:String!){battle(battleId:$b){roundNumber currentNarration battleData actionOptions}}',
      { b: battle.battleId },
    );
    await continuityTour({ page, click, shot, graphql, id, evidence, tools, chat });
    await shot('ending', 'Veyloria. Your party. Your choices. One continuing world.', async () => {
      await wait(2200);
    });
    recording = page.video();
  } finally {
    const recordedEnd = Date.now();
    await context.close();
    recording = recording || page.video();
    if (recording) await recording.saveAs(path.join(dir, 'adventure.webm'));
    writeFileSync(
      path.join(dir, 'timeline.json'),
      JSON.stringify(
        {
          started,
          recordedEnd,
          sync,
          viewport: { width: 1920, height: 960 },
          chunkMetadata: [...pcmMetadata.entries()],
          beats,
          audio: audio.map((a) => ({ ...a, at: (a.at - started) / 1000 })),
          evidence,
        },
        null,
        2,
      ),
    );
  }
});
