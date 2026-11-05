const test = require('node:test');
const assert = require('node:assert');
const { planCustom } = require('../core/mods');

const H = 'a'.repeat(128);
const list = [
  { name: 'a', file: 'a.jar', url: 'https://github.com/x/a.jar', sha512: H, side: 'both' },
  { name: 'b', file: 'b.jar', url: 'https://github.com/x/b.jar', sha512: H, side: 'client' },
  { name: 'c', file: 'c.jar', url: 'https://github.com/x/c.jar', sha512: H, side: 'server' }
];

test('client: both + client only', () => {
  const r = planCustom(list, 'client');
  assert.deepStrictEqual(r.map(x => x.filename), ['a.jar', 'b.jar']);
});
test('server excluded for client, client excluded for server', () => {
  assert.deepStrictEqual(planCustom(list, 'server').map(x => x.filename), ['a.jar', 'c.jar']);
});
test('empty/undefined -> []', () => {
  assert.deepStrictEqual(planCustom(undefined, 'client'), []);
  assert.deepStrictEqual(planCustom([], 'client'), []);
});
test('preserves filename/url/sha512', () => {
  assert.deepStrictEqual(planCustom([list[0]], 'client'), [{ filename: 'a.jar', url: 'https://github.com/x/a.jar', sha512: H }]);
});
