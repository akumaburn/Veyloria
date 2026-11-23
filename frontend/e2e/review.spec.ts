import {
  test,
  expect,
  type Page,
  type BrowserContext,
  type APIRequestContext,
} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
const output = process.env.REVIEW_DIR!;
const fixture = JSON.parse(readFileSync(path.join(output, 'fixture.json'), 'utf8'));
const { id, owner, member, outsider } = fixture;
const screenshots = path.join(output, 'screenshots');
mkdirSync(screenshots, { recursive: true });

async function identity(context: BrowserContext, actor = owner) {
  await context.addInitScript(({ accessToken, refreshToken }) => {
    localStorage.setItem('authToken', accessToken);
    localStorage.setItem('refreshToken', refreshToken);
    localStorage.setItem('veyloria-debug-mode', 'false');
  }, actor);
}
async function gql(
  request: APIRequestContext,
  query: string,
  variables: Record<string, unknown> = {},
  actor = owner,
  allowErrors = false,
) {
  const res = await request.post(process.env.REVIEW_API_URL!, {
    headers: { Authorization: `Bearer ${actor.accessToken}` },
    data: { query, variables },
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  if (!allowErrors) expect(body.errors, JSON.stringify(body.errors)).toBeUndefined();
  return allowErrors ? body : body.data;
}
async function capture(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(350); // Capture settled CSS entrances, not their first faded frame.
  await page.screenshot({ path: path.join(screenshots, `${name}.png`), fullPage: false });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
}
async function chat(page: Page) {
  await page.goto(`/chat/${id.conversation}`);
  await expect(
    page.getByText('Evening settled over Lantern Quay.', { exact: false }),
  ).toBeVisible();
}
async function tools(page: Page, tab: string) {
  if (!(await page.getByTitle(tab, { exact: true }).isVisible()))
    await page.getByRole('button', { name: 'RP Tools', exact: true }).click();
  await page.getByTitle(tab, { exact: true }).click();
}

test('public authentication, actual login, protected routes and keyboard access', async ({
  page,
}) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: /sign in/i })).toBeVisible();
  await capture(page, 'login');
  await page.getByLabel(/Username or email/).fill(owner.user.username);
  await page.getByLabel(/Password/).fill(fixture.password);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).not.toHaveURL(/login/);
  await page.goto('/characters');
  await expect(page.getByRole('heading', { name: 'Characters', exact: true })).toBeVisible();
  await page.getByRole('tab', { name: /^Characters/ }).click();
  await expect(page.getByText('Mira Solstice', { exact: true }).first()).toBeVisible();
  await page.keyboard.press('Tab');
  await capture(page, 'characters');
});

test('participant access, outsider denial, authoritative inventory effects and transfer', async ({
  request,
}) => {
  const query = 'query($id:String!){conversation(id:$id){id title}}';
  expect((await gql(request, query, { id: id.conversation }, member)).conversation.id).toBe(
    id.conversation,
  );
  const denied = await gql(request, query, { id: id.conversation }, outsider, true);
  expect(denied.errors?.length || !denied.data?.conversation).toBeTruthy();
  const use =
    'mutation($item:String!,$target:String){useInventoryItem(inventoryId:$item,targetCharacterId:$target){inventoryId quantity}}';
  expect(
    (await gql(request, use, { item: id.potion, target: id.mira })).useInventoryItem.quantity,
  ).toBe(1);
  const stats = await gql(
    request,
    'query($id:String!,$c:String!){characterStats(conversationId:$id,characterId:$c){statName statValue}}',
    { id: id.conversation, c: id.mira },
  );
  expect(
    stats.characterStats.find((s: { statName: string; statValue: number }) => s.statName === 'HP')
      .statValue,
  ).toBe(17);
  expect(
    (await gql(request, use, { item: id.potion, target: id.mira }, outsider, true)).errors?.length,
  ).toBeGreaterThan(0);
  const transfer =
    'mutation($item:String!,$user:String,$qty:Int){transferInventoryItem(inventoryId:$item,targetUserId:$user,quantity:$qty){inventoryId quantity}}';
  const invalid = await gql(
    request,
    transfer,
    { item: id.potion, user: member.user.id, qty: 99 },
    owner,
    true,
  );
  expect(invalid.errors?.length).toBeGreaterThan(0);
});

test('real chat, narrator controls, inventory, dice and character panels', async ({
  page,
  context,
}) => {
  await identity(context);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await chat(page);
  await capture(page, 'conversation');
  await tools(page, 'Inventory');
  await expect(page.getByText('Healing draught', { exact: true })).toBeVisible();
  await capture(page, 'inventory');
  await tools(page, 'Characters');
  await capture(page, 'companions');
  await tools(page, 'Dice');
  await expect(page.getByText('Dice Roller', { exact: false }).first()).toBeVisible();
  await expect(page.getByTitle('AI', { exact: true })).toHaveCount(0);
  await page.getByRole('checkbox', { name: 'Narrator', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('checkbox', { name: 'Narrator', exact: true })).toBeChecked();
  await expect(page.getByTitle('AI', { exact: true })).toBeVisible();
  await tools(page, 'AI');
  await page.getByRole('checkbox', { name: 'Narrator', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('checkbox', { name: 'Narrator', exact: true })).not.toBeChecked();
  await expect(page.getByTitle('AI', { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('map local/global 2D and 3D, narrator gating, return to conversation and mobile layout', async ({
  page,
  context,
}) => {
  await identity(context);
  await page.goto(`/map/${id.conversation}`);
  await expect(page.getByRole('button', { name: 'Local', exact: true })).toBeVisible();
  await expect(page.getByText('Market Square', { exact: true }).first()).toBeVisible();
  await capture(page, 'local-map');
  await page.getByRole('button', { name: 'Global', exact: true }).click();
  await page.getByRole('button', { name: '2D View', exact: true }).click();
  await expect(page.locator('canvas').first()).toBeVisible();
  await capture(page, 'global-2d');
  await page.getByRole('button', { name: '3D View', exact: true }).click();
  await expect(page.locator('canvas').first()).toBeVisible();
  await page.waitForTimeout(1500); // Allow WebGL shader compilation before visual evidence.
  await capture(page, 'global-3d');
  await expect(page.getByRole('button', { name: 'Editor', exact: true })).toHaveCount(0);
  await page.getByRole('checkbox', { name: 'Narrator mode', exact: true }).check();
  await page.getByRole('button', { name: 'Editor', exact: true }).click();
  await page.getByRole('checkbox', { name: 'Narrator mode', exact: true }).uncheck();
  await expect(page.getByRole('button', { name: 'Editor', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Local', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await capture(page, 'local-map-mobile');
  await page.getByRole('link', { name: 'Back to conversation', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/chat/${id.conversation}$`));
  await expect(
    page.getByText('Evening settled over Lantern Quay.', { exact: false }),
  ).toBeVisible();
  await capture(page, 'conversation-mobile');
});

test('speech planning uses the character for first person, spells acronyms and keeps narrator design stable', async ({
  request,
}) => {
  const character = {
    id: id.mira,
    name: 'Mira Solstice',
    gender: 'female',
    pronouns: 'she/her',
    ageYears: 26,
    personality: 'Impulsive, fiercely loyal, and cynical about institutional power.',
  };
  const plan = async (text: string, c?: Record<string, unknown>) => {
    const res = await request.post(`${process.env.REVIEW_BASE_URL}/tts/api/speech-plan`, {
      data: { text, character: c, language_id: 'en' },
    });
    expect(res.ok()).toBeTruthy();
    return (await res.json()).segments;
  };
  const inner = await plan('*I tuck a stray strand behind my ear.* "CID sent us."', character);
  expect(inner.length).toBeGreaterThan(0);
  expect(
    inner.every((s: { instruction: string; text: string }) => /woman|female/i.test(s.instruction)),
  ).toBeTruthy();
  expect(inner.map((s: { instruction: string; text: string }) => s.text).join(' ')).toContain(
    'C I D',
  );
  expect((await plan('The lanterns glowed above the square.'))[0].instruction).toBe(
    (await plan('The tide turned beneath the bridge.'))[0].instruction,
  );
});

test('settings categories, speech availability, dark/mobile routes and accessibility evidence', async ({
  page,
  context,
}) => {
  await identity(context);
  await page.goto('/settings');
  await page.getByRole('button', { name: /Text-to-Speech/ }).click();
  await expect(page.getByText('Loading available voices...', { exact: false })).toHaveCount(0);
  await capture(page, 'voice-settings');
  const audit = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  writeFileSync(path.join(output, 'accessibility-settings.json'), JSON.stringify(audit, null, 2));
  expect(audit.violations.filter((v) => ['critical', 'serious'].includes(v.impact || ''))).toEqual(
    [],
  );
  for (const route of ['/roleplay', '/party', '/achievements', '/community', '/profile']) {
    await page.goto(route);
    await expect(page.locator('main')).toBeVisible();
    await expect(page.getByText('Something went wrong', { exact: true })).toHaveCount(0);
    await capture(page, route.slice(1));
  }
  await page.goto('/settings');
  await page.getByRole('button', { name: /Appearance/ }).click();
  await page.getByRole('button', { name: 'Dark', exact: true }).click();
  await expect(page.locator('html')).toHaveClass(/dark/);
  await capture(page, 'settings-dark');
  await page.getByRole('button', { name: 'Light', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await capture(page, 'settings-mobile');
});

test('movement runs focused judgments, commits location and saves arrival before a resident greeting', async ({
  page,
  context,
  request,
}) => {
  await identity(context);
  await chat(page);
  await page
    .getByRole('textbox', { name: 'Message input', exact: true })
    .fill('Let us go to the Lantern Inn now.');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  await expect
    .poll(
      async () => {
        const data = await gql(
          request,
          'query($id:String!){worldState(conversationId:$id){currentAreaId currentArea}}',
          { id: id.conversation },
        );
        return data.worldState.currentAreaId;
      },
      { timeout: 75_000 },
    )
    .toBe(id.inn);
  await expect
    .poll(
      async () => {
        const data = await gql(
          request,
          'query($id:String!){messages(conversationId:$id){items{id content messageType characterId}}}',
          { id: id.conversation },
        );
        return data.messages.items.filter(
          (m: { content: string; messageType: string; characterId: string | null }) =>
            /reached the Lantern Inn/.test(m.content || '') &&
            m.messageType.toUpperCase() === 'NARRATION',
        ).length;
      },
      { timeout: 75_000 },
    )
    .toBeGreaterThan(0);
  await expect(page.getByText(/The inn is just across the square/).first()).toBeVisible();
  await capture(page, 'arrival');
  const calls = readFileSync(path.join(output, 'model-calls.jsonl'), 'utf8')
    .trim()
    .split('\n')
    .map((l) => JSON.parse(l));
  expect(calls.some((c) => c.kind === 'travel')).toBeTruthy();
  expect(calls.some((c) => c.kind === 'encounter')).toBeTruthy();
  // Confirmed travel deliberately skips the independent interaction judgment.
  expect(calls.filter((c) => c.kind === 'bookkeeping')).toHaveLength(4);
  await expect
    .poll(
      async () => {
        const data = await gql(
          request,
          'query($id:String!){messages(conversationId:$id){items{id content messageType characterId}}}',
          { id: id.conversation },
        );
        const rows = [...data.messages.items].reverse();
        const arrival = rows.findIndex(
          (m: { content: string; messageType: string; characterId: string | null }) =>
            /reached the Lantern Inn/.test(m.content || '') &&
            m.messageType.toUpperCase() === 'NARRATION',
        );
        return rows
          .slice(arrival + 1)
          .some(
            (m: { content: string; messageType: string; characterId: string | null }) =>
              m.characterId && ![id.mira, id.torren].includes(m.characterId),
          );
      },
      { timeout: 20_000 },
    )
    .toBeTruthy();
  await expect(page.getByText(/The inn is just across the square/).first()).toBeVisible();
  await capture(page, 'arrival');
});

test('ordered voice playback uses the real WebSocket adapter and skips player speech', async ({
  page,
  context,
}) => {
  await identity(context);
  const spoken: string[] = [];
  const errors: string[] = [];
  page.on('websocket', (socket) => {
    if (!socket.url().includes('/ws/tts')) return;
    socket.on('framesent', (frame) => {
      try {
        const payload = JSON.parse(String(frame.payload));
        if (payload.text) spoken.push(payload.text);
      } catch {
        /* Other protocol frames are not speech requests. */
      }
    });
    socket.on('framereceived', (frame) => {
      try {
        const payload = JSON.parse(String(frame.payload));
        if (payload.type === 'ERROR') errors.push(payload.message);
      } catch {
        /* PCM transport can contain binary data. */
      }
    });
  });
  await chat(page);
  await page.getByRole('button', { name: 'Read conversation', exact: true }).click();
  await expect(page.getByText('All loaded replies read', { exact: true })).toBeVisible({
    timeout: 30_000,
  });
  expect(spoken.length).toBeGreaterThan(1);
  expect(spoken[0]).toContain('Evening settled');
  expect(spoken.some((text) => text.includes('I will keep watch by the market'))).toBeFalsy();
  expect(errors).toEqual([]);
  await expect(page.getByRole('button', { name: 'Read again', exact: true })).toBeVisible();
});

test('combat separates adjudication/prose/options, accepts member votes and rejects stale rounds', async ({
  request,
  page,
  context,
}) => {
  const opened = await gql(
    request,
    'mutation($input:InitiateBattleInput!){initiateBattle(input:$input){battleId roundNumber status actionOptions}}',
    {
      input: {
        conversationId: id.conversation,
        enemyName: 'Bridge Sentinel',
        enemyDescription: 'A clockwork guardian blocks the crossing.',
        enemyHp: 20,
        narrativeContext: 'The guardian stands between the party and the observatory.',
      },
    },
  );
  const battle = opened.initiateBattle;
  expect(battle.status).toBe('ACTIVE');
  const vote =
    'mutation($input:VoteActionInput!){voteAction(input:$input){userId roundNumber optionIndex}}';
  const choice = { battleId: battle.battleId, optionIndex: 0, roundNumber: battle.roundNumber };
  expect((await gql(request, vote, { input: choice }, member)).voteAction.userId).toBe(
    member.user.id,
  );
  expect(
    (await gql(request, vote, { input: choice }, outsider, true)).errors?.length,
  ).toBeGreaterThan(0);
  const resolveRound =
    'mutation($b:String!,$r:Int){resolveBattleRound(battleId:$b,roundNumber:$r){battleId roundNumber currentNarration}}';
  expect(
    (await gql(request, resolveRound, { b: battle.battleId, r: battle.roundNumber }))
      .resolveBattleRound.roundNumber,
  ).toBe(battle.roundNumber + 1);
  expect(
    (await gql(request, resolveRound, { b: battle.battleId, r: battle.roundNumber }, owner, true))
      .errors?.length,
  ).toBeGreaterThan(0);
  await identity(context);
  await chat(page);
  await tools(page, 'Battle');
  await expect(page.getByText('Bridge Sentinel', { exact: true }).last()).toBeVisible();
  await capture(page, 'combat');
  const calls = readFileSync(path.join(output, 'model-calls.jsonl'), 'utf8')
    .trim()
    .split('\n')
    .map((l) => JSON.parse(l));
  for (const kind of ['battle-adjudication', 'battle-narration', 'battle-options'])
    expect(calls.some((c) => c.kind === kind)).toBeTruthy();
});
