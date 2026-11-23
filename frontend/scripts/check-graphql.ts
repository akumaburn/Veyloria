import { CodegenContext } from '@graphql-codegen/cli';
import {
  Kind,
  NoUnusedFragmentsRule,
  print,
  specifiedRules,
  validate,
  visit,
  type FragmentDefinitionNode,
  type OperationDefinitionNode,
} from 'graphql';

// Reuse codegen's source loader without generating or overwriting application files.
const endpoint = process.env.GRAPHQL_SCHEMA_URL || 'http://localhost:8300/graphql';
const context = new CodegenContext({ config: { generates: {}, config: { noRequire: true } } });
const schema = await context.loadSchema(endpoint);
const sources = await context.loadDocuments(
  ['src/**/*.{ts,tsx,graphql}', '!src/**/*.{test,spec}.{ts,tsx}', '!src/**/generated/**'],
  'standard',
);
const fragments = new Map<string, FragmentDefinitionNode>();
const operations: { file: string; definition: OperationDefinitionNode }[] = [];
let failures = 0;
for (const source of sources) {
  for (const definition of source.document?.definitions || []) {
    if (definition.kind === Kind.FRAGMENT_DEFINITION) {
      const previous = fragments.get(definition.name.value);
      if (previous && print(previous) !== print(definition)) {
        console.error(`Conflicting definitions for fragment ${definition.name.value}`);
        failures++;
      }
      fragments.set(definition.name.value, definition);
    } else if (definition.kind === Kind.OPERATION_DEFINITION) {
      operations.push({ file: source.location || '<unknown>', definition });
    }
  }
}
for (const { file, definition } of operations) {
  const required = new Map<string, FragmentDefinitionNode>();
  const includeFragments = (node: OperationDefinitionNode | FragmentDefinitionNode) => {
    visit(node, {
      FragmentSpread(spread) {
        if (required.has(spread.name.value)) return;
        const fragment = fragments.get(spread.name.value);
        if (fragment) {
          required.set(spread.name.value, fragment);
          includeFragments(fragment);
        }
      },
    });
  };
  includeFragments(definition);
  const errors = validate(schema, {
    kind: Kind.DOCUMENT,
    definitions: [definition, ...required.values()],
  });
  for (const error of errors) {
    console.error(`${file}: ${definition.name?.value || '<anonymous>'}: ${error.message}`);
    failures++;
  }
}
// Check standalone fragments too, even if no current operation references them.
for (const fragment of fragments.values()) {
  const required = new Map<string, FragmentDefinitionNode>();
  const include = (definition: FragmentDefinitionNode) => {
    if (required.has(definition.name.value)) return;
    required.set(definition.name.value, definition);
    visit(definition, {
      FragmentSpread(spread) {
        const dependency = fragments.get(spread.name.value);
        if (dependency) include(dependency);
      },
    });
  };
  include(fragment);
  const errors = validate(
    schema,
    { kind: Kind.DOCUMENT, definitions: [...required.values()] },
    specifiedRules.filter((rule) => rule !== NoUnusedFragmentsRule),
  );
  for (const error of errors) {
    console.error(`Fragment ${fragment.name.value}: ${error.message}`);
    failures++;
  }
}
if (!operations.length) {
  console.error('No GraphQL operations found; check the source patterns.');
  failures++;
}
process.stdout.write(
  `Validated ${operations.length} operations and ${fragments.size} fragments against ${endpoint}: ${failures} errors.\n`,
);
process.exitCode = failures ? 1 : 0;
