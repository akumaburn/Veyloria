import { expect, type Page, type Locator } from '@playwright/test';

type Tour = {
  page: Page;
  click: (target: Locator) => Promise<void>;
  shot: (name: string, caption: string, action: () => Promise<void>) => Promise<void>;
  graphql: (query: string, variables?: Record<string, unknown>) => Promise<any>;
  id: Record<string, string>;
  evidence: Record<string, unknown>;
  tools: (tab: string) => Promise<void>;
  chat: () => Promise<void>;
};

export async function creationTour(h: Tour) {
  const { page, click, shot, graphql, evidence } = h;
  await page.goto('/roleplay');
  await shot('premise', 'Begin with a premise, a template, or a shared adventure.', async () => {
    await expect(page.getByRole('heading', { name: 'Roleplay', exact: true })).toBeVisible();
    await page.waitForTimeout(2200);
  });
  await shot('templates', 'Story templates offer many worlds to begin in.', async () => {
    await click(page.getByRole('button', { name: 'Templates', exact: true }));
    await expect(page.getByText('Tavern Tales', { exact: false }).first()).toBeVisible({
      timeout: 20000,
    });
    await page.waitForTimeout(1800);
  });
  await page.goto('/characters');
  await shot(
    'character-prompt',
    'Describe someone worth meeting. Generate a character from your idea.',
    async () => {
      await click(page.getByRole('button', { name: 'Generate with AI', exact: true }));
      await page
        .getByRole('textbox', { name: 'Character description', exact: true })
        .fill(
          'Create Seren Vale, a kind young adult woman who charts the stars from a coastal observatory. Give her a thoughtful personality, a secret regret, a warm speaking style, and a reason to join a wandering party.',
        );
      await page.waitForTimeout(1600);
    },
  );
  await click(page.getByRole('button', { name: 'Generate', exact: true }));
  await expect(page.getByRole('form', { name: 'Character editor' })).toBeVisible({
    timeout: 180000,
  });
  await shot(
    'character-result',
    'Review the generated identity, appearance, personality and history.',
    async () => {
      await expect(page.getByRole('textbox', { name: /^Name/ })).not.toHaveValue('');
      evidence.generatedCharacterName = await page
        .getByRole('textbox', { name: /^Name/ })
        .inputValue();
      await page.waitForTimeout(1400);
      await click(page.getByRole('tab', { name: 'Story', exact: true }));
      await page.waitForTimeout(1600);
    },
  );
  await click(page.getByRole('button', { name: 'Create Character', exact: true }));
  await expect(
    page.getByRole('button', { name: 'Generate roster', exact: true }).first(),
  ).toBeVisible({ timeout: 20000 });
  await shot(
    'roster-prompt',
    'Generate a connected cast from one shared world and history.',
    async () => {
      await click(page.getByRole('button', { name: 'Generate roster', exact: true }).first());
      await page
        .getByLabel('Location, world & history')
        .fill(
          'Lantern Quay is a coastal town beneath an abandoned observatory. Generate exactly three adult companions: a stargazer, a harbor ranger, and a guild envoy. They share the memory of a lost expedition. Give them distinct names, roles, alignments, and explicit non-neutral feelings toward one another: affection, trust, or rivalry. Every member has a meaningful connection to the others.',
        );
      await page.getByRole('slider', { name: 'Number of characters' }).focus();
      await page.keyboard.press('Home');
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(1800);
    },
  );
  await click(
    page.getByRole('dialog').getByRole('button', { name: 'Generate roster', exact: true }),
  );
  await expect(page.getByRole('heading', { name: 'Review your roster', exact: true })).toBeVisible({
    timeout: 240000,
  });
  await shot(
    'roster-result',
    'A shared setting, three distinct lives, and connections between them.',
    async () => {
      await page.getByPlaceholder('Name this roster').fill('Hearts of Lantern Quay');
      await page.waitForTimeout(2200);
    },
  );
  await click(page.getByRole('button', { name: 'Save all 3 characters', exact: true }));
  await expect(
    page.getByRole('heading', { name: 'Hearts of Lantern Quay', exact: true }),
  ).toBeVisible({ timeout: 30000 });
  evidence.roster = (
    await graphql('query{characterGroups{id name characterIds}}')
  ).characterGroups.find((group: { name: string }) => group.name === 'Hearts of Lantern Quay');
  expect((evidence.roster as { characterIds: string[] }).characterIds).toHaveLength(3);
  await shot(
    'roster-saved',
    'Save the ensemble and bring its members into future stories together.',
    async () => {
      await page.waitForTimeout(2400);
    },
  );
  await click(page.getByRole('button', { name: 'Relationships', exact: true }).first());
  await shot(
    'relationships',
    'Inspect every saved connection. Feelings have a direction.',
    async () => {
      await expect(page.getByRole('region', { name: 'Saved relationships' })).toBeVisible({
        timeout: 20000,
      });
      const rows = page.getByRole('region', { name: 'Saved relationships' }).getByRole('button');
      expect(await rows.count()).toBeGreaterThan(0);
      await click(rows.first());
      await page.waitForTimeout(1800);
    },
  );
  await shot(
    'relationship-direction',
    'Affection, trust and rivalry can differ in each direction.',
    async () => {
      await click(
        page.getByRole('button', { name: 'Reverse relationship direction', exact: true }),
      );
      await page.waitForTimeout(2200);
    },
  );
  await page.keyboard.press('Escape');
  await click(page.getByRole('button', { name: 'Alignment', exact: true }).first());
  await shot('alignment', 'Alignment gives each character a lasting moral compass.', async () => {
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.waitForTimeout(2600);
  });
  await page.keyboard.press('Escape');
  const workshop = (
    await graphql(
      'mutation($input:CreateConversationInput!){createConversation(input:$input){id}}',
      { input: { title: 'Worldbuilding Workshop' } },
    )
  ).createConversation;
  await page.goto('/map/' + workshop.id);
  await click(page.getByRole('checkbox', { name: 'Narrator mode', exact: true }));
  await click(page.getByRole('button', { name: 'Global', exact: true }));
  await shot(
    'map-design',
    'Shape a procedural world: scale, climate, water and a repeatable seed.',
    async () => {
      await expect(page.getByPlaceholder('The Shattered Isles')).toBeVisible();
      await page.getByPlaceholder('The Shattered Isles').fill('The Moonlit Reaches');
      await page.getByPlaceholder('Random if left empty').fill('moonlit-overview');
      await page.waitForTimeout(2200);
    },
  );
  await click(page.getByRole('button', { name: 'Generate Map', exact: true }));
  await shot(
    'map-generated',
    'Terrain, settlements, cultures and roads emerge as one connected world.',
    async () => {
      await expect(page.locator('canvas').first()).toBeVisible({ timeout: 60000 });
      await page.waitForTimeout(2200);
    },
  );
}

export async function continuityTour(h: Tour) {
  const { page, click, shot, tools, graphql, id, evidence } = h;
  await shot(
    'dice',
    'Let chance enter the story, with a visible roll and a recorded purpose.',
    async () => {
      await tools('Dice');
      await page.getByPlaceholder('e.g. 2d6+3').fill('2d6+3');
      await page.getByPlaceholder('e.g. Perception check').fill('Read the observatory inscription');
      await click(page.getByRole('button', { name: 'Roll', exact: true }));
      await expect(page.getByRole('heading', { name: 'Recent Rolls', exact: true })).toBeVisible();
      await page.waitForTimeout(1600);
    },
  );
  await shot(
    'bookmarks',
    'Keep a moment close. Save a bookmark before another road unfolds.',
    async () => {
      await tools('Bookmarks');
      await click(page.getByRole('button', { name: 'Create Bookmark', exact: true }));
      await page.getByPlaceholder('Bookmark label').fill('Beneath the observatory stars');
      await click(page.getByRole('button', { name: 'Save Bookmark', exact: true }));
      await expect(page.getByText('Beneath the observatory stars', { exact: true })).toBeVisible();
      await page.waitForTimeout(1600);
    },
  );
  await click(page.getByRole('checkbox', { name: 'Narrator', exact: true }).locator('..'));
  await tools('Bookmarks');
  await shot(
    'branching',
    'Narrator mode can branch from a saved moment into another possibility.',
    async () => {
      await click(page.getByRole('button', { name: 'Main Branch', exact: true }));
      await click(page.getByRole('button', { name: 'New Branch', exact: true }));
      await page
        .getByLabel('Branch Point', { exact: true })
        .selectOption({ label: 'The road to the inn' });
      await page.getByLabel('Branch Name', { exact: true }).fill('The road not taken');
      await click(page.getByRole('button', { name: 'Create Branch', exact: true }));
      await expect(
        page.getByRole('heading', { name: 'Create Story Branch', exact: true }),
      ).not.toBeVisible();
      await click(page.getByRole('button', { name: 'Main Branch', exact: true }));
      await expect(page.getByRole('button', { name: /The road not taken/ })).toBeVisible();
      await page.waitForTimeout(1800);
    },
  );
  // Close the inspected branch menu before navigating to another tool.
  await click(page.getByRole('button', { name: 'Main Branch', exact: true }).last());
  await shot(
    'world-state',
    'The world keeps its place, its time, and the consequences of the journey.',
    async () => {
      await tools('World');
      await page.waitForTimeout(2400);
    },
  );
  await shot(
    'knowledge',
    'Narrator tools expose the memories and knowledge behind the unfolding story.',
    async () => {
      await tools('Knowledge');
      await expect(page.getByText(/The observatory lens/).first()).toBeVisible();
      await page.waitForTimeout(2200);
    },
  );
  await shot(
    'agents',
    'Choose models for individual agents and inspect their work in Narrator mode.',
    async () => {
      await tools('AI');
      await page.waitForTimeout(2400);
    },
  );
  await shot(
    'illustrations',
    'Optional ComfyUI illustrations — the image provider is not connected in this capture.',
    async () => {
      await tools('Images');
      await page.waitForTimeout(2400);
    },
  );
  await click(page.getByRole('checkbox', { name: 'Narrator', exact: true }).locator('..'));
  await shot(
    'publish',
    'Give the journey a title, a synopsis, and a place in the community.',
    async () => {
      await click(page.getByTitle('Publish Story', { exact: true }));
      await page
        .getByPlaceholder('Enter a title for your story')
        .fill('Lanterns Beneath the Stars');
      await page
        .getByPlaceholder('Write a brief synopsis or description...')
        .fill(
          'A shared journey through Lantern Quay, where old roads, unexpected friendships, and a forgotten observatory draw a party together.',
        );
      await click(page.getByRole('button', { name: 'Publish', exact: true }));
      await expect(
        page.getByRole('heading', { name: 'Publish Story', exact: true }),
      ).not.toBeVisible({ timeout: 30000 });
    },
  );
  await page.goto('/community');
  await shot(
    'community',
    'Publish, discover, and read stories shared by the community.',
    async () => {
      await click(page.getByText('Lanterns Beneath the Stars', { exact: true }).first());
      await expect(
        page.getByRole('heading', { name: 'Lanterns Beneath the Stars', exact: true }),
      ).toBeVisible();
      await page.waitForTimeout(2500);
    },
  );
  await page.goto('/party');
  await shot(
    'party',
    'Invite others to walk beside you. Form a party and share its invitation.',
    async () => {
      await click(page.getByRole('button', { name: 'Create a party', exact: true }));
      await page.getByLabel('Party Name', { exact: true }).fill('The Lantern Companions');
      await page
        .getByLabel('Description (optional)', { exact: true })
        .fill('Friends following the old observatory road.');
      await click(page.getByRole('button', { name: 'Create Party', exact: true }));
      await expect(page.getByText('Party Created!', { exact: true })).toBeVisible();
      await page.waitForTimeout(2200);
    },
  );
  await page.goto('/achievements');
  await shot(
    'achievements',
    'A record of the milestones, experience and stories gathered along the way.',
    async () => {
      await expect(page.getByRole('heading', { name: 'Achievements', exact: true })).toBeVisible();
      await page.waitForTimeout(2600);
    },
  );
  await portabilityTour(h);
}

export async function portabilityTour(h:Tour){
  const {page,click,shot,evidence}=h;
  await page.goto('/data');
  await shot(
    'portability',
    'Keep a copy of your world. Export your stories, characters and settings.',
    async () => {
      const downloaded = page.waitForEvent('download');
      await click(page.getByRole('button', { name: 'Export Selected Data', exact: true }));
      const file = await downloaded;
      evidence.exportFile = file.suggestedFilename();
      await page.waitForTimeout(2200);
    },
  );
  await page.goto('/settings');
  await shot(
    'settings',
    'Make the experience your own, from model choices to voice and appearance.',
    async () => {
      await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
      await page.waitForTimeout(2600);
    },
  );
  evidence.overviewCompleted = true;
  await h.chat();
}
