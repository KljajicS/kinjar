import { after, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, collection, query, where, Bytes, Timestamp, serverTimestamp, setLogLevel, onSnapshot } from 'firebase/firestore';

setLogLevel('silent');

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-kinjar', firestore: {
    host: '127.0.0.1', port: 8088, rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8'),
  } });
});
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());
function db(uid, provider = 'google.com') {
  return env.authenticatedContext(uid, { email: `${uid}@example.com`, email_verified: true,
    firebase: { sign_in_provider: provider } }).firestore();
}
const family = (members = { owner: 'm-owner' }) => ({ owner: 'owner', admins: ['owner'], members, slot: '0',
  activeDataset: 'current', otherDataset: '', generation: 1, status: 'ACTIVE', action: 'create', target: '', inviteSlot: '' });
const legacyState = () => ({ count: 0, bytes: 0, day: serverTimestamp(), dailyCount: 0, lastOpId: '' });
const state = () => ({ ...legacyState(), dailyBytes: 0 });
const initialState = () => ({ ...state(), chunks: 0, chunkBytes: 0, expectedChunks: 1,
  expectedBytes: 3, digest: '0'.repeat(64), lastChunk: '', sealed: false, historyVersion: 2 });
async function seed(members, overrides = {}) {
  await env.withSecurityRulesDisabled(async context => {
    const d = context.firestore();
    await setDoc(doc(d, 'families/family'), { ...family(members), ...overrides });
    await setDoc(doc(d, 'slots/0'), { familyId: 'family', owner: 'owner' });
    await setDoc(doc(d, 'families/family/datasets/current'), state());
    await setDoc(doc(d, 'families/family/installs/0'), { uid: 'owner', installId: 'device' });
    await setDoc(doc(d, 'families/family/claims/device'), { uid: 'owner' });
    for (const [uid, memberId] of Object.entries(members ?? { owner: 'm-owner' })) {
      await setDoc(doc(d, `users/${uid}`), { familyId: 'family', memberId, status: 'ACTIVE' });
    }
  });
}
function operation() {
  return { uid: 'owner', installId: 'device', installSlot: '0', sequence: 1,
    payload: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])), cost: 3, createdAt: serverTimestamp() };
}
function upload(d, options = {}) {
  const id = options.id ?? 'device:1';
  const batch = writeBatch(d);
  batch.set(doc(d, `families/family/datasets/current/operations/${id}`), { ...operation(), sequence: Number(id.split(':')[1]), ...options.operation });
  batch.update(doc(d, 'families/family/datasets/current'), { ...state(), count: 1, bytes: 3, dailyCount: 1, dailyBytes: 3,
    lastOpId: id, ...options.state });
  return batch;
}
// The write shape of app versions released before operation costs and the daily byte budget.
function legacyUpload(d) {
  const { cost, ...op } = operation();
  const batch = writeBatch(d);
  batch.set(doc(d, 'families/family/datasets/current/operations/device:1'), op);
  batch.update(doc(d, 'families/family/datasets/current'), { ...legacyState(), count: 1, bytes: 3, dailyCount: 1, lastOpId: 'device:1' });
  return batch;
}
async function setState(data, path = 'families/family/datasets/current') {
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), path), data));
}

test('member uploads immutable content with exact coupled counters', async () => {
  await seed();
  const d = db('owner');
  await assertSucceeds(upload(d).commit());
  await assertFails(setDoc(doc(d, 'families/family/datasets/current/operations/device:1'), operation()));
  const s = await getDoc(doc(d, 'families/family/datasets/current'));
  assert.equal(s.data().bytes, 3);
});
test('unauthenticated, foreign family and non-Google access are rejected', async () => {
  await seed();
  for (const d of [env.unauthenticatedContext().firestore(), db('stranger'), db('owner', 'password')]) {
    await assertFails(getDoc(doc(d, 'families/family/datasets/current')));
    await assertFails(upload(d).commit());
  }
});
test('counter cannot move without content and content cannot bypass counter', async () => {
  await seed();
  const d = db('owner');
  await assertFails(updateDoc(doc(d, 'families/family/datasets/current'), { count: 1, bytes: 3, lastOpId: 'device:1' }));
  await assertFails(setDoc(doc(d, 'families/family/datasets/current/operations/device:1'), operation()));
  await assertFails(upload(d, { state: { bytes: 2 } }).commit());
  await assertFails(upload(d, { state: { dailyCount: 0 } }).commit());
});
test('one counter increment cannot authorize two operation documents', async () => {
  await seed();
  const d = db('owner');
  const batch = upload(d);
  batch.set(doc(d, 'families/family/datasets/current/operations/device:2'), { ...operation(), sequence: 2 });
  await assertFails(batch.commit());
});
test('forged timestamp, extra fields and unregistered installation are rejected', async () => {
  await seed();
  const d = db('owner');
  await assertFails(upload(d, { operation: { createdAt: Timestamp.fromMillis(0) } }).commit());
  await assertFails(upload(d, { operation: { unbounded: 'extra' } }).commit());
  await assertFails(upload(d, { operation: { installSlot: '5' } }).commit());
});
test('quota exhaustion blocks cloud writes without deleting existing content', async () => {
  await seed();
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/current'), { ...state(), dailyCount: 200 }));
  await assertFails(upload(db('owner'), { state: { dailyCount: 201 } }).commit());
});
test('invited account can read family metadata in last invitation slot but not finances', async () => {
  await seed();
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/5'), {
    familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com', status: 'PENDING', uid: '',
    createdAt: Timestamp.fromMillis(Date.now()),
  }));
  await assertSucceeds(getDoc(doc(db('wife'), 'families/family')));
  await assertFails(getDoc(doc(db('wife'), 'families/family/datasets/current')));
});
test('invitation acceptance requires membership and user reference in same commit', async () => {
  await seed();
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/0'), {
    familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com', status: 'PENDING', uid: '',
    createdAt: Timestamp.fromMillis(Date.now()),
  }));
  const d = db('wife');
  const batch = writeBatch(d);
  batch.update(doc(d, 'families/family'), { members: { owner: 'm-owner', wife: 'm-wife' }, action: 'join', target: 'wife', inviteSlot: '0' });
  batch.update(doc(d, 'families/family/invites/0'), { status: 'ACCEPTED', uid: 'wife' });
  batch.set(doc(d, 'users/wife'), { familyId: 'family', memberId: 'm-wife', status: 'ACTIVE' });
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(doc(d, 'families/family/datasets/current')));
});
test('ordinary member cannot promote self or erase final administrator', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  await assertFails(updateDoc(doc(db('wife'), 'families/family'), { admins: ['owner', 'wife'], action: 'roles' }));
  await assertFails(updateDoc(doc(db('owner'), 'families/family'), { admins: [], action: 'roles' }));
});
test('removal closes finance access and keeps own removal proof readable', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const d = db('owner');
  const batch = writeBatch(d);
  batch.update(doc(d, 'families/family'), { members: { owner: 'm-owner' }, action: 'remove', target: 'wife' });
  batch.update(doc(d, 'users/wife'), { status: 'REMOVED' });
  await assertSucceeds(batch.commit());
  await assertFails(getDoc(doc(db('wife'), 'families/family/datasets/current')));
  assert.equal((await getDoc(doc(db('wife'), 'users/wife'))).data().status, 'REMOVED');
});

async function admit(uid, data = { enabled: true }) {
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), `admission/${uid}`), data));
}
function createWorkspace(d, uid, fid, slot) {
  const b = writeBatch(d);
  b.set(doc(d, `slots/${slot}`), { familyId: fid, owner: uid });
  b.set(doc(d, `families/${fid}`), { ...family({ [uid]: `m-${uid}` }), owner: uid, admins: [uid], slot, status: 'INITIALIZING' });
  b.set(doc(d, `users/${uid}`), { familyId: fid, memberId: `m-${uid}`, status: 'ACTIVE' });
  return b.commit();
}

test('family creation reserves one public slot and links account atomically', async () => {
  await admit('owner');
  const d = db('owner');
  const create = (fid, slot) => {
    const b = writeBatch(d);
    b.set(doc(d, `slots/${slot}`), { familyId: fid, owner: 'owner' });
    b.set(doc(d, `families/${fid}`), { ...family(), slot, status: 'INITIALIZING' });
    b.set(doc(d, 'users/owner'), { familyId: fid, memberId: 'm-owner', status: 'ACTIVE' });
    return b.commit();
  };
  await assertFails(create('out-of-range', '10'));
  await assertSucceeds(create('family', '0'));
  await assertFails(create('second', '1'));
  await assertSucceeds(setDoc(doc(d, 'families/family/datasets/current'), initialState()));
});
test('only administrator-approved accounts can create a workspace', async () => {
  await assertFails(createWorkspace(db('outsider'), 'outsider', 'mine', '0'));
  await admit('disabled', { enabled: false });
  await assertFails(createWorkspace(db('disabled'), 'disabled', 'mine', '0'));
  await admit('string', { enabled: 'true' });
  await assertFails(createWorkspace(db('string'), 'string', 'mine', '0'));
  await admit('creator');
  await assertFails(createWorkspace(db('creator', 'password'), 'creator', 'mine', '0'));
  await assertSucceeds(createWorkspace(db('creator'), 'creator', 'mine', '0'));
  assert.equal((await getDoc(doc(db('creator'), 'families/mine'))).data().owner, 'creator');
});
test('clients cannot grant, change or read workspace admission of other accounts', async () => {
  await admit('creator');
  const outsider = db('outsider');
  await assertFails(setDoc(doc(outsider, 'admission/outsider'), { enabled: true }));
  await assertFails(setDoc(doc(outsider, 'admission/creator'), { enabled: false }));
  await assertFails(getDoc(doc(outsider, 'admission/creator')));
  await assertSucceeds(getDoc(doc(outsider, 'admission/outsider')));
  const creator = db('creator');
  await assertSucceeds(getDoc(doc(creator, 'admission/creator')));
  await assertFails(updateDoc(doc(creator, 'admission/creator'), { enabled: false }));
  await assertFails(deleteDoc(doc(creator, 'admission/creator')));
});
test('existing members without admission keep reading, syncing and joining by invitation', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  await assertSucceeds(getDoc(doc(db('wife'), 'families/family/datasets/current')));
  await assertSucceeds(upload(db('owner')).commit());
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/0'), invitation('kid@example.com')));
  const kid = db('kid');
  const join = writeBatch(kid);
  join.update(doc(kid, 'families/family'), { members: { owner: 'm-owner', wife: 'm-wife', kid: 'm-kid' }, action: 'join', target: 'kid', inviteSlot: '0' });
  join.update(doc(kid, 'families/family/invites/0'), { status: 'ACCEPTED', uid: 'kid' });
  join.set(doc(kid, 'users/kid'), { familyId: 'family', memberId: 'm-kid', status: 'ACTIVE' });
  await assertSucceeds(join.commit());
});
test('ordinary users cannot claim a slot or forge family reference separately', async () => {
  const d = db('owner');
  await assertFails(setDoc(doc(d, 'slots/0'), { familyId: 'family', owner: 'owner' }));
  await assertFails(setDoc(doc(d, 'users/owner'), { familyId: 'family', memberId: 'm-owner', status: 'ACTIVE' }));
});
for (const administrator of [false, true]) {
  test(`${administrator ? 'administrator' : 'member'} can leave and create a separate family`, async () => {
    await seed({ owner: 'm-owner', wife: 'm-wife' }, { admins: administrator ? ['owner', 'wife'] : ['owner'] });
    const d = db('wife');
    await env.withSecurityRulesDisabled(async context => {
      await setDoc(doc(context.firestore(), 'families/family/installs/1'), { uid: 'wife', installId: 'wifephone' });
      await setDoc(doc(context.firestore(), 'families/family/claims/wifephone'), { uid: 'wife' });
    });
    await assertSucceeds(upload(d, { id: 'wifephone:1', operation: { uid: 'wife', installId: 'wifephone', installSlot: '1' } }).commit());
    const contribution = 'families/family/datasets/current/operations/wifephone:1';
    const before = (await getDoc(doc(db('owner'), contribution))).data();
    const batch = writeBatch(d);
    batch.update(doc(d, 'families/family'), { members: { owner: 'm-owner' }, admins: ['owner'], action: 'remove', target: 'wife' });
    batch.update(doc(d, 'users/wife'), { status: 'REMOVED' });
    batch.delete(doc(d, 'families/family/installs/1'));
    await assertSucceeds(batch.commit());
    await assertFails(getDoc(doc(d, 'families/family/datasets/current')));
    assert.deepEqual((await getDoc(doc(db('owner'), contribution))).data(), before);
    assert.equal((await getDoc(doc(db('owner'), 'families/family'))).data().owner, 'owner');
    await admit('wife');
    await assertSucceeds(createWorkspace(d, 'wife', 'new-family', '1'));
    assert.deepEqual((await getDoc(doc(db('owner'), contribution))).data(), before);
  });
}

test('self removal cannot remove the owner, another member, or omit the account update', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife', child: 'm-child' });
  for (const [actor, target] of [['owner', 'owner'], ['wife', 'child']]) {
    const d = db(actor);
    const members = { owner: 'm-owner', wife: 'm-wife', child: 'm-child' };
    delete members[target];
    const batch = writeBatch(d);
    batch.update(doc(d, 'families/family'), { members, action: 'remove', target });
    batch.update(doc(d, `users/${target}`), { status: 'REMOVED' });
    await assertFails(batch.commit());
  }
  await assertFails(updateDoc(doc(db('wife'), 'families/family'), {
    members: { owner: 'm-owner', child: 'm-child' }, action: 'remove', target: 'wife',
  }));
  await assertFails(updateDoc(doc(db('wife'), 'users/wife'), { status: 'REMOVED' }));
});

test('owner can appoint admin but cannot transfer ownership to outsider', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const d = db('owner');
  await assertSucceeds(updateDoc(doc(d, 'families/family'), { admins: ['owner', 'wife'], action: 'roles' }));
  await assertFails(updateDoc(doc(d, 'families/family'), { owner: 'stranger', admins: ['owner', 'stranger'], action: 'transfer' }));
  await assertFails(updateDoc(doc(d, 'families/family'), { owner: 'wife', action: 'transfer' }));
  const transfer = writeBatch(d);
  transfer.update(doc(d, 'families/family'), { owner: 'wife', action: 'transfer' });
  transfer.update(doc(d, 'slots/0'), { owner: 'wife' });
  await assertSucceeds(transfer.commit());
  await assertFails(updateDoc(doc(d, 'families/family'), { admins: ['owner'], action: 'roles' }));
});
test('lifetime operation count and bytes do not stop subsequent uploads', async () => {
  await seed();
  const d = db('owner');
  await setState({ ...state(), count: 100000, bytes: 104857600 });
  await assertSucceeds(upload(d, { state: { count: 100001, bytes: 104857603 } }).commit());
  const saved = (await getDoc(doc(d, 'families/family/datasets/current'))).data();
  assert.equal(saved.count, 100001);
  assert.equal(saved.bytes, 104857603);
  await assertFails(updateDoc(doc(d, 'families/family/datasets/current'), { count: 0, bytes: 0 }));
});
test('individual operation size remains bounded without a cumulative history cap', async () => {
  await seed();
  const size = 524289;
  await assertFails(upload(db('owner'), { operation: { payload: Bytes.fromUint8Array(new Uint8Array(size)), cost: size },
    state: { bytes: size, dailyBytes: size } }).commit());
});
test('daily usage resets only on server day change', async () => {
  await seed();
  const yesterday = Timestamp.fromMillis(Date.now() - 86400000);
  await setState({ ...state(), day: yesterday, dailyCount: 200, dailyBytes: 8388608 });
  await assertFails(upload(db('owner'), { state: { day: yesterday, dailyCount: 1 } }).commit());
  await assertSucceeds(upload(db('owner')).commit());
});
test('operation cost covers its payload and stays within one day of bytes', async () => {
  await seed();
  const d = db('owner');
  await assertFails(upload(d, { operation: { cost: 2 }, state: { dailyBytes: 2 } }).commit());
  await assertFails(upload(d, { operation: { cost: 8388609 }, state: { dailyBytes: 8388609 } }).commit());
  await assertFails(upload(d, { operation: { cost: 4000 }, state: { dailyBytes: 3 } }).commit());
  await assertFails(upload(d, { operation: { cost: '3' } }).commit());
  await assertSucceeds(upload(d, { operation: { cost: 8388608 }, state: { dailyBytes: 8388608 } }).commit());
  assert.equal((await getDoc(doc(d, 'families/family/datasets/current/operations/device:1'))).data().cost, 8388608);
});
test('daily byte budget blocks uploads past eight MiB until the next server day', async () => {
  await seed();
  const d = db('owner');
  await setState({ ...state(), dailyCount: 5, dailyBytes: 8388605 });
  await assertFails(upload(d, { operation: { cost: 4 }, state: { dailyCount: 6, dailyBytes: 8388609 } }).commit());
  await assertSucceeds(upload(d, { state: { dailyCount: 6, dailyBytes: 8388608 } }).commit());
  await assertFails(upload(d, { id: 'device:2', state: { count: 2, bytes: 6, dailyCount: 7, dailyBytes: 3 } }).commit());
  const yesterday = Timestamp.fromMillis(Date.now() - 86400000);
  await setState({ ...state(), day: yesterday, dailyCount: 7, dailyBytes: 8388608 });
  await assertSucceeds(upload(d, { id: 'device:2', state: { count: 1, bytes: 3, dailyCount: 1, dailyBytes: 3 } }).commit());
});
test('daily count and byte budgets reset together', async () => {
  await seed();
  const d = db('owner');
  await setState({ ...state(), dailyCount: 10, dailyBytes: 1000 });
  await assertFails(upload(d, { state: { dailyCount: 11, dailyBytes: 3 } }).commit());
  await assertFails(upload(d, { state: { dailyCount: 1, dailyBytes: 1003 } }).commit());
  const yesterday = Timestamp.fromMillis(Date.now() - 86400000);
  await setState({ ...state(), day: yesterday, dailyCount: 10, dailyBytes: 1000 });
  await assertFails(upload(d, { state: { dailyCount: 1, dailyBytes: 1003 } }).commit());
  await assertFails(upload(d, { state: { dailyCount: 11, dailyBytes: 3 } }).commit());
  await assertSucceeds(upload(d, { state: { dailyCount: 1, dailyBytes: 3 } }).commit());
});
test('dataset without daily bytes starts its budget at zero and refuses the old write shape', async () => {
  await seed();
  const d = db('owner');
  await setState({ ...legacyState(), count: 7, bytes: 21, dailyCount: 7 });
  await assertFails(legacyUpload(d).commit());
  await assertFails(upload(d, { state: { count: 8, bytes: 24, dailyCount: 8, dailyBytes: 4 } }).commit());
  await assertSucceeds(upload(d, { state: { count: 8, bytes: 24, dailyCount: 8, dailyBytes: 3 } }).commit());
  assert.equal((await getDoc(doc(d, 'families/family/datasets/current'))).data().dailyBytes, 3);
});
test('cleanup decrements cannot reset the daily budget', async () => {
  await seed(undefined, { otherDataset: 'old' });
  const d = db('owner');
  const stateRef = doc(d, 'families/family/datasets/old');
  await setState({ ...state(), count: 1, bytes: 3, dailyCount: 200, dailyBytes: 8388608, lastOpId: 'device:1' }, 'families/family/datasets/old');
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/old/operations/device:1'), operation()));
  const remove = (extra) => {
    const b = writeBatch(d);
    b.delete(doc(d, 'families/family/datasets/old/operations/device:1'));
    b.update(stateRef, { count: 0, bytes: 0, lastOpId: 'device:1', ...extra });
    return b.commit();
  };
  await assertFails(remove({ dailyBytes: 0 }));
  await assertFails(remove({ dailyCount: 0 }));
  await assertFails(remove({ day: Timestamp.fromMillis(0) }));
  await assertSucceeds(remove());
  const saved = (await getDoc(stateRef)).data();
  assert.equal(saved.dailyBytes, 8388608);
  assert.equal(saved.dailyCount, 200);
});
test('invitation creation is bounded and cannot name another inviter', async () => {
  await seed();
  const d = db('owner');
  const invite = { familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com', status: 'PENDING', uid: '',
    createdAt: serverTimestamp() };
  await assertSucceeds(setDoc(doc(d, 'families/family/invites/0'), invite));
  await assertFails(setDoc(doc(d, 'families/family/invites/6'), invite));
  await assertFails(setDoc(doc(d, 'families/family/invites/1'), { ...invite, inviter: 'stranger' }));
  await assertFails(setDoc(doc(d, 'families/family/invites/1'), { ...invite, createdAt: Timestamp.fromMillis(Date.now() + 9 * 86400000) }));
});

test('cancelled invitation cannot grant membership and can be deleted by admin', async () => {
  await seed();
  const owner = db('owner');
  const ref = doc(owner, 'families/family/invites/0');
  await setDoc(ref, { familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com',
    createdAt: serverTimestamp(), status: 'PENDING', uid: '' });
  await assertFails(deleteDoc(ref));
  await assertSucceeds(updateDoc(ref, { status: 'CANCELLED' }));
  const wife = db('wife');
  const join = writeBatch(wife);
  join.update(doc(wife, 'families/family'), { members: { owner: 'm-owner', wife: 'm-wife' }, action: 'join', target: 'wife', inviteSlot: '0' });
  join.update(doc(wife, 'families/family/invites/0'), { status: 'ACCEPTED', uid: 'wife' });
  join.set(doc(wife, 'users/wife'), { familyId: 'family', memberId: 'm-wife', status: 'ACTIVE' });
  await assertFails(join.commit());
  await assertSucceeds(deleteDoc(ref));
});

test('racing administrator role edits cannot remove creator or elevate a member', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife', child: 'm-child' }, { admins: ['owner', 'wife'] });
  await Promise.all([
    assertSucceeds(updateDoc(doc(db('owner'), 'families/family'), { admins: ['owner'], action: 'roles' })),
    assertFails(updateDoc(doc(db('wife'), 'families/family'), { admins: ['wife', 'child'], action: 'roles' })),
  ]);
  assert.deepEqual((await getDoc(doc(db('owner'), 'families/family'))).data().admins, ['owner']);
});

for (const actor of ['owner', 'wife']) {
test(`member removal by ${actor}, all six device slots and six invitation cancellations fit atomic access limits`, async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' }, { admins: ['owner', 'wife'] });
  await env.withSecurityRulesDisabled(async c => {
    for (let i = 0; i < 6; i++) {
      await setDoc(doc(c.firestore(), `families/family/installs/${i}`), { uid: 'wife', installId: `device${i}` });
      await setDoc(doc(c.firestore(), `families/family/invites/${i}`), invitation(`kid${i}@example.com`, Date.now(), 'wife'));
    }
  });
  const d = db(actor);
  const remove = writeBatch(d);
  remove.update(doc(d, 'families/family'), { members: { owner: 'm-owner' }, admins: ['owner'], action: 'remove', target: 'wife' });
  remove.update(doc(d, 'users/wife'), { status: 'REMOVED' });
  for (let i = 0; i < 6; i++) {
    remove.delete(doc(d, `families/family/installs/${i}`));
    remove.update(doc(d, `families/family/invites/${i}`), { status: 'CANCELLED' });
  }
  await assertSucceeds(remove.commit());
  for (let i = 0; i < 6; i++) {
    assert.equal((await getDoc(doc(db('owner'), `families/family/installs/${i}`))).exists(), false);
    assert.equal((await getDoc(doc(db('owner'), `families/family/invites/${i}`))).data().status, 'CANCELLED');
  }
});
}

function register(d, uid, installId, slot, { claim = true } = {}) {
  const b = writeBatch(d);
  b.set(doc(d, `families/family/installs/${slot}`), { uid, installId, name: 'Phone', createdAt: serverTimestamp() });
  if (claim) b.set(doc(d, `families/family/claims/${installId}`), { uid, installSlot: slot, createdAt: serverTimestamp() });
  return b.commit();
}
test('a new installation claims its ID permanently in the same commit that registers it', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const wife = db('wife');
  await assertFails(setDoc(doc(wife, 'families/family/claims/unregistered'), { uid: 'wife', installSlot: '1', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(wife, 'families/family/claims/unregistered'), { uid: 'wife', installSlot: '0', createdAt: serverTimestamp() }));
  await assertFails(register(wife, 'wife', 'wifephone', '1', { claim: false }));
  await assertFails(register(wife, 'owner', 'wifephone', '1'));
  await assertSucceeds(register(wife, 'wife', 'wifephone', '1'));
  for (const d of [wife, db('owner')]) {
    await assertFails(updateDoc(doc(d, 'families/family/claims/wifephone'), { uid: 'owner' }));
    await assertFails(deleteDoc(doc(d, 'families/family/claims/wifephone')));
  }
  await assertFails(getDocs(collection(wife, 'families/family/claims')));
  await assertSucceeds(deleteDoc(doc(wife, 'families/family/installs/1')));
  await assertSucceeds(register(wife, 'wife', 'wifephone', '1', { claim: false }));
  const b = writeBatch(wife);
  b.set(doc(wife, 'families/family/datasets/current/operations/wifephone:1'), { ...operation(), uid: 'wife', installId: 'wifephone', installSlot: '1' });
  b.update(doc(wife, 'families/family/datasets/current'), { ...state(), count: 1, bytes: 3, dailyCount: 1, dailyBytes: 3, lastOpId: 'wifephone:1' });
  await assertSucceeds(b.commit());
});
test('another member cannot occupy operation IDs of a claimed installation, even from a legacy slot', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const wife = db('wife');
  await assertFails(register(wife, 'wife', 'device', '1'));
  await assertFails(register(wife, 'wife', 'device', '1', { claim: false }));
  await assertFails(setDoc(doc(wife, 'families/family/claims/device'), { uid: 'wife', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(db('stranger'), 'families/family/claims/fresh'), { uid: 'stranger', createdAt: serverTimestamp() }));
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/installs/2'), { uid: 'wife', installId: 'device' }));
  await assertFails(upload(wife, { operation: { uid: 'wife', installSlot: '2' } }).commit());
  await assertSucceeds(upload(db('owner')).commit());
});
test('an installation registered before claims cannot upload until its claim is backfilled', async () => {
  await seed();
  await env.withSecurityRulesDisabled(async c => deleteDoc(doc(c.firestore(), 'families/family/claims/device')));
  const d = db('owner');
  await assertFails(upload(d).commit());
  await assertSucceeds(setDoc(doc(d, 'families/family/claims/device'), { uid: 'owner', installSlot: '0', createdAt: serverTimestamp() }));
  await assertSucceeds(upload(d).commit());
});

function joinFamily(uid, slot, members) {
  const d = db(uid);
  const b = writeBatch(d);
  b.update(doc(d, 'families/family'), { members, action: 'join', target: uid, inviteSlot: slot });
  b.update(doc(d, `families/family/invites/${slot}`), { status: 'ACCEPTED', uid });
  b.set(doc(d, `users/${uid}`), { familyId: 'family', memberId: `m-${uid}`, status: 'ACTIVE' });
  return b.commit();
}
test('invitations stop admitting once their inviter is demoted, and a current administrator can invite again', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' }, { admins: ['owner', 'wife'] });
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/0'), invitation('kid@example.com', Date.now(), 'wife')));
  const kid = db('kid');
  await assertSucceeds(getDoc(doc(kid, 'families/family')));
  await assertSucceeds(updateDoc(doc(db('owner'), 'families/family'), { admins: ['owner'], action: 'roles' }));
  await assertFails(getDoc(doc(kid, 'families/family')));
  await assertSucceeds(getDoc(doc(kid, 'families/family/invites/0')));
  const members = { owner: 'm-owner', wife: 'm-wife', kid: 'm-kid' };
  await assertFails(joinFamily('kid', '0', members));
  await assertFails(updateDoc(doc(kid, 'families/family/invites/0'), { status: 'ACCEPTED', uid: 'kid' }));
  await assertFails(setDoc(doc(db('wife'), 'families/family/invites/1'), { ...invitation('kid@example.com', Date.now(), 'wife'), createdAt: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(db('owner'), 'families/family/invites/1'), { ...invitation('kid@example.com'), createdAt: serverTimestamp() }));
  await assertSucceeds(joinFamily('kid', '1', members));
});
test('invitations stop admitting once their inviter is removed', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' }, { admins: ['owner', 'wife'] });
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/0'), invitation('kid@example.com', Date.now(), 'wife')));
  const owner = db('owner');
  const remove = writeBatch(owner);
  remove.update(doc(owner, 'families/family'), { members: { owner: 'm-owner' }, admins: ['owner'], action: 'remove', target: 'wife' });
  remove.update(doc(owner, 'users/wife'), { status: 'REMOVED' });
  await assertSucceeds(remove.commit());
  await assertFails(joinFamily('kid', '0', { owner: 'm-owner', kid: 'm-kid' }));
  await assertSucceeds(updateDoc(doc(owner, 'families/family/invites/0'), { status: 'CANCELLED' }));
});

test('bootstrap requires complete immutable chunks before activation', async () => {
  await seed(undefined, { status: 'INITIALIZING' });
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/current'), initialState()));
  const d = db('owner');
  const stateRef = doc(d, 'families/family/datasets/current');
  const chunkRef = doc(d, 'families/family/datasets/current/chunks/0');
  const payload = Bytes.fromUint8Array(new Uint8Array([1, 2, 3]));
  const activate = () => {
    const b = writeBatch(d);
    b.update(stateRef, { sealed: true });
    b.update(doc(d, 'families/family'), { status: 'ACTIVE', action: 'activate' });
    return b.commit();
  };
  await assertFails(activate());
  await assertFails(setDoc(chunkRef, { payload }));
  await assertFails(updateDoc(stateRef, { chunks: 1, chunkBytes: 3, lastChunk: '0' }));
  const b = writeBatch(d);
  b.set(chunkRef, { payload });
  b.update(stateRef, { chunks: 1, chunkBytes: 3, lastChunk: '0' });
  await assertSucceeds(b.commit());
  await assertFails(setDoc(chunkRef, { payload }));
  await assertSucceeds(activate());
  await assertFails(updateDoc(stateRef, { sealed: false }));
  await assertSucceeds(upload(d).commit());
  await assertFails(upload(d, { state: { chunkBytes: 0 } }).commit());
});

test('live membership listener reports removal while finance listener loses access', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const wife = db('wife');
  const unsubscribers = [];
  let start;
  const started = new Promise(resolve => { start = resolve; });
  const revoked = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Listener revocation timed out')), 10000);
    unsubscribers.push(onSnapshot(doc(wife, 'families/family/datasets/current'), () => start(), error => {
      clearTimeout(timeout);
      error.code === 'permission-denied' ? resolve() : reject(error);
    }));
  });
  const removed = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Removal proof timed out')), 10000);
    unsubscribers.push(onSnapshot(doc(wife, 'users/wife'), snapshot => {
      if (snapshot.data()?.status === 'REMOVED') { clearTimeout(timeout); resolve(); }
    }, reject));
  });
  try {
    await started;
    const owner = db('owner');
    const batch = writeBatch(owner);
    batch.update(doc(owner, 'families/family'), { members: { owner: 'm-owner' }, action: 'remove', target: 'wife' });
    batch.update(doc(owner, 'users/wife'), { status: 'REMOVED' });
    await assertSucceeds(batch.commit());
    await assertSucceeds(upload(owner).commit());
    await Promise.all([revoked, removed]);
  } finally { unsubscribers.forEach(unsubscribe => unsubscribe()); }
});

async function startRestore(d, target = 'replacement', token = 'restore-token') {
  return updateDoc(doc(d, 'families/family'), { status: 'RESTORING', action: 'restore', generation: 2,
    otherDataset: target, restoreToken: token, restoreUid: 'owner', restoreStartedAt: serverTimestamp() });
}
async function stageRestore(d) {
  const stateRef = doc(d, 'families/family/datasets/replacement');
  await setDoc(stateRef, { ...initialState(), restoreToken: 'restore-token' });
  const b = writeBatch(d);
  b.set(doc(d, 'families/family/datasets/replacement/chunks/0'), { payload: operation().payload });
  b.update(stateRef, { chunks: 1, chunkBytes: 3, lastChunk: '0' });
  return b.commit();
}
function activateRestore(d) {
  const b = writeBatch(d);
  b.update(doc(d, 'families/family/datasets/replacement'), { sealed: true });
  b.update(doc(d, 'families/family'), { status: 'ACTIVE', action: 'restored', activeDataset: 'replacement', otherDataset: 'current' });
  return b.commit();
}
test('restore fences old uploads and atomically activates complete data without changing members', async () => {
  await seed({ owner: 'm-owner', wife: 'm-wife' });
  const d = db('owner');
  await assertFails(startRestore(db('wife')));
  await assertSucceeds(startRestore(d));
  await assertFails(startRestore(d, 'second', 'other-token'));
  await assertFails(upload(d).commit());
  await assertFails(activateRestore(d));
  await assertSucceeds(stageRestore(d));
  await assertSucceeds(activateRestore(d));
  assert.deepEqual((await getDoc(doc(d, 'families/family'))).data().members, { owner: 'm-owner', wife: 'm-wife' });
  await assertFails(upload(d).commit());
  await assertFails(startRestore(d, 'another'));
});
test('version 2 restore streams past the former 128 chunk and 64 MiB caps without partial activation', async () => {
  await seed();
  const d = db('owner');
  await assertSucceeds(startRestore(d));
  const stateRef = doc(d, 'families/family/datasets/replacement');
  const chunkCount = 129;
  const full = Bytes.fromUint8Array(new Uint8Array(524288));
  const totalBytes = chunkCount * 524288;
  assert.ok(totalBytes > 67108864);
  const manifest = { ...initialState(), expectedChunks: chunkCount, expectedBytes: totalBytes, restoreToken: 'restore-token' };
  await assertFails(setDoc(stateRef, { ...manifest, expectedBytes: totalBytes + 1 }));
  await assertSucceeds(setDoc(stateRef, manifest));
  for (let index = 0; index < chunkCount; index++) {
    const batch = writeBatch(d);
    batch.set(doc(d, `families/family/datasets/replacement/chunks/${index}`), { payload: full });
    batch.update(stateRef, { chunks: index + 1, chunkBytes: (index + 1) * 524288, lastChunk: String(index) });
    await assertSucceeds(batch.commit());
    if (index === 127) await assertFails(activateRestore(d));
  }
  await assertSucceeds(activateRestore(d));
  assert.equal((await getDoc(stateRef)).data().chunkBytes, totalBytes);
  assert.equal((await getDoc(doc(d, 'families/family'))).data().activeDataset, 'replacement');
});
test('version 2 manifests keep technical bounds, accept wide chunk ids and refuse version 1 bootstraps', async () => {
  await seed(undefined, { status: 'INITIALIZING' });
  await env.withSecurityRulesDisabled(async c => deleteDoc(doc(c.firestore(), 'families/family/datasets/current')));
  const d = db('owner');
  const stateRef = doc(d, 'families/family/datasets/current');
  const { historyVersion, ...legacyManifest } = initialState();
  const { dailyBytes, ...withoutDailyBytes } = initialState();
  await assertFails(setDoc(stateRef, legacyManifest));
  await assertFails(setDoc(stateRef, { ...initialState(), historyVersion: 1 }));
  await assertFails(setDoc(stateRef, withoutDailyBytes));
  await assertFails(setDoc(stateRef, { ...initialState(), expectedChunks: 1, expectedBytes: 524289 }));
  await assertFails(setDoc(stateRef, { ...initialState(), expectedChunks: 4, expectedBytes: 3 }));
  await assertFails(setDoc(stateRef, { ...initialState(), expectedChunks: 1, expectedBytes: 2 ** 53 }));
  const manifest = { ...initialState(), expectedChunks: 1001, expectedBytes: 3003 };
  await assertSucceeds(setDoc(stateRef, manifest));
  const add = (cid, chunks, chunkBytes) => {
    const b = writeBatch(d);
    b.set(doc(d, `families/family/datasets/current/chunks/${cid}`), { payload: operation().payload });
    b.update(stateRef, { chunks, chunkBytes, lastChunk: cid });
    return b.commit();
  };
  await assertFails(add('01000', 1, 3));
  await assertFails(add('1001', 1, 3));
  await assertSucceeds(add('1000', 1, 3));
  await assertSucceeds(add('0', 2, 6));
  const activate = writeBatch(d);
  activate.update(stateRef, { sealed: true });
  activate.update(doc(d, 'families/family'), { status: 'ACTIVE', action: 'activate' });
  await assertFails(activate.commit());
});
test('bootstraps accept history versions 2 and 3 only', async () => {
  await seed(undefined, { status: 'INITIALIZING' });
  await env.withSecurityRulesDisabled(async c => deleteDoc(doc(c.firestore(), 'families/family/datasets/current')));
  const stateRef = doc(db('owner'), 'families/family/datasets/current');
  await assertFails(setDoc(stateRef, { ...initialState(), historyVersion: 4 }));
  await assertFails(setDoc(stateRef, { ...initialState(), historyVersion: '3' }));
  await assertSucceeds(setDoc(stateRef, { ...initialState(), historyVersion: 3 }));
  await env.withSecurityRulesDisabled(async c => deleteDoc(doc(c.firestore(), 'families/family/datasets/current')));
  await assertSucceeds(setDoc(stateRef, initialState()));
});
test('a version 1 bootstrap started before the update can finish without changing its manifest', async () => {
  await seed(undefined, { status: 'INITIALIZING' });
  const { historyVersion, ...legacyManifest } = initialState();
  await setState({ ...legacyManifest, expectedChunks: 2, expectedBytes: 6 });
  const d = db('owner');
  const b = writeBatch(d);
  b.set(doc(d, 'families/family/datasets/current/chunks/0'), { payload: operation().payload });
  b.update(doc(d, 'families/family/datasets/current'), { chunks: 1, chunkBytes: 3, lastChunk: '0' });
  await assertSucceeds(b.commit());
  const finish = writeBatch(d);
  finish.set(doc(d, 'families/family/datasets/current/chunks/1'), { payload: operation().payload });
  finish.update(doc(d, 'families/family/datasets/current'), { chunks: 2, chunkBytes: 6, lastChunk: '1' });
  await assertSucceeds(finish.commit());
  const activate = writeBatch(d);
  activate.update(doc(d, 'families/family/datasets/current'), { sealed: true });
  activate.update(doc(d, 'families/family'), { status: 'ACTIVE', action: 'activate' });
  await assertSucceeds(activate.commit());
});
test('expired restore cannot activate and administrators can cancel it', async () => {
  await seed();
  const d = db('owner');
  await assertSucceeds(startRestore(d));
  await assertSucceeds(stageRestore(d));
  await env.withSecurityRulesDisabled(async c => updateDoc(doc(c.firestore(), 'families/family'), { restoreStartedAt: Timestamp.fromMillis(0) }));
  await assertFails(activateRestore(d));
  await assertSucceeds(updateDoc(doc(d, 'families/family'), { status: 'ACTIVE', action: 'cancelRestore' }));
  await assertFails(activateRestore(d));
  await assertSucceeds(upload(d).commit());
});
test('retired cleanup cannot delete active data or move counters without deletion', async () => {
  await seed();
  const d = db('owner');
  await assertSucceeds(startRestore(d));
  await assertSucceeds(stageRestore(d));
  await env.withSecurityRulesDisabled(async c => updateDoc(doc(c.firestore(), 'families/family'), { restoreStartedAt: Timestamp.fromMillis(0) }));
  await assertSucceeds(updateDoc(doc(d, 'families/family'), { status: 'ACTIVE', action: 'cancelRestore' }));
  const stateRef = doc(d, 'families/family/datasets/replacement');
  const chunkRef = doc(d, 'families/family/datasets/replacement/chunks/0');
  const eraseOnly = writeBatch(d);
  eraseOnly.delete(chunkRef);
  await assertFails(eraseOnly.commit());
  await assertFails(updateDoc(stateRef, { chunks: 0, chunkBytes: 0, lastChunk: '0' }));
  const b = writeBatch(d);
  b.delete(chunkRef);
  b.update(stateRef, { chunks: 0, chunkBytes: 0, lastChunk: '0' });
  await assertSucceeds(b.commit());
  const finish = writeBatch(d);
  finish.delete(stateRef);
  finish.update(doc(d, 'families/family'), { otherDataset: '', action: 'cleaned' });
  await assertSucceeds(finish.commit());
  const active = writeBatch(d);
  active.delete(doc(d, 'families/family/datasets/current'));
  await assertFails(active.commit());
});

test('family deletion requires removal proofs and complete cleanup before releasing its public slot', async () => {
  const members = { owner: 'm-owner', a: 'a', b: 'b', c: 'c', d: 'd', e: 'e' };
  await seed(members);
  await env.withSecurityRulesDisabled(async c => {
    const d = c.firestore();
    await setDoc(doc(d, 'slots/0'), { familyId: 'family', owner: 'owner' });
    await setDoc(doc(d, 'families/family/datasets/current'), { ...initialState(), expectedBytes: 3 });
  });
  const d = db('owner');
  await assertFails(updateDoc(doc(d, 'families/family'), { status: 'DELETING', action: 'delete', deletionPhase: 0 }));
  const start = writeBatch(d);
  start.update(doc(d, 'families/family'), { status: 'DELETING', action: 'delete', deletionPhase: 0 });
  for (const uid of Object.keys(members)) start.update(doc(d, `users/${uid}`), { status: 'REMOVED' });
  await assertSucceeds(start.commit());
  await assertFails(getDoc(doc(db('a'), 'families/family/datasets/current')));
  assert.equal((await getDoc(doc(db('a'), 'users/a'))).data().status, 'REMOVED');
  const early = writeBatch(d);
  early.delete(doc(d, 'families/family'));
  early.delete(doc(d, 'slots/0'));
  await assertFails(early.commit());
  await assertSucceeds(updateDoc(doc(d, 'families/family'), { deletionPhase: 1, action: 'deleteSlots' }));
  await assertFails(deleteDoc(doc(db('a'), 'families/family/claims/device')));
  await assertSucceeds(deleteDoc(doc(d, 'families/family/claims/device')));
  assert.equal((await getDocs(collection(d, 'families/family/claims'))).size, 0);
  await assertFails(updateDoc(doc(d, 'families/family'), { deletionPhase: 2, action: 'deleteSlots' }));
  const installs = writeBatch(d);
  installs.delete(doc(d, 'families/family/installs/0'));
  installs.update(doc(d, 'families/family'), { deletionPhase: 2, action: 'deleteSlots' });
  await assertSucceeds(installs.commit());
  const stateDelete = writeBatch(d);
  stateDelete.delete(doc(d, 'families/family/datasets/current'));
  await assertSucceeds(stateDelete.commit());
  const finish = writeBatch(d);
  finish.delete(doc(d, 'families/family'));
  finish.delete(doc(d, 'slots/0'));
  await assertSucceeds(finish.commit());
});

const invitation = (email, createdAt = Date.now(), inviter = 'owner') => ({ familyId: 'family', email, inviter, inviterEmail: `${inviter}@example.com`,
  status: 'PENDING', uid: '', createdAt: Timestamp.fromMillis(createdAt) });
async function seedPersonal(members = { wife: 'm-wife' }, invitedAt = Date.now()) {
  await seed();
  await env.withSecurityRulesDisabled(async c => {
    const d = c.firestore();
    await setDoc(doc(d, 'families/personal'), { ...family(members), owner: 'wife', admins: ['wife'], slot: '1' });
    await setDoc(doc(d, 'slots/1'), { familyId: 'personal', owner: 'wife' });
    await setDoc(doc(d, 'families/personal/datasets/current'), state());
    await setDoc(doc(d, 'families/personal/installs/0'), { uid: 'wife', installId: 'phone' });
    await setDoc(doc(d, 'families/personal/claims/phone'), { uid: 'wife' });
    for (const [uid, memberId] of Object.entries(members)) {
      await setDoc(doc(d, `users/${uid}`), { familyId: 'personal', memberId, status: 'ACTIVE' });
    }
    await setDoc(doc(d, 'families/family/invites/0'), invitation('wife@example.com', invitedAt));
  });
}
function switchFamily(d, { archive = true, join = true } = {}) {
  const b = writeBatch(d);
  if (archive) b.update(doc(d, 'families/personal'), { status: 'ARCHIVED', action: 'archive', target: 'family', inviteSlot: '' });
  if (join) {
    b.update(doc(d, 'families/family'), { members: { owner: 'm-owner', wife: 'm-joined' }, action: 'join', target: 'wife', inviteSlot: '0' });
    b.update(doc(d, 'families/family/invites/0'), { status: 'ACCEPTED', uid: 'wife' });
  }
  b.set(doc(d, 'users/wife'), { familyId: 'family', memberId: 'm-joined', status: 'ACTIVE' });
  return b.commit();
}
function uploadPersonal(d, sequence) {
  const b = writeBatch(d);
  b.set(doc(d, `families/personal/datasets/current/operations/phone:${sequence}`),
    { ...operation(), uid: 'wife', installId: 'phone', sequence });
  b.update(doc(d, 'families/personal/datasets/current'), { ...state(), count: sequence, bytes: 3 * sequence,
    dailyCount: sequence, dailyBytes: 3 * sequence, lastOpId: `phone:${sequence}` });
  return b.commit();
}
async function personalStatus() {
  let status;
  await env.withSecurityRulesDisabled(async c => { status = (await getDoc(doc(c.firestore(), 'families/personal'))).data().status; });
  return status;
}

test('personal workspace joins an invited family by archiving, keeping its history readable to its owner only', async () => {
  await seedPersonal();
  const d = db('wife');
  await assertSucceeds(uploadPersonal(d, 1));
  await assertSucceeds(switchFamily(d));
  const archive = (await getDoc(doc(d, 'families/personal'))).data();
  assert.equal(archive.status, 'ARCHIVED');
  assert.deepEqual(archive.members, { wife: 'm-wife' });
  await assertSucceeds(getDoc(doc(d, 'families/personal/datasets/current/operations/phone:1')));
  await assertSucceeds(getDoc(doc(d, 'families/family/datasets/current')));
  await assertFails(getDoc(doc(db('owner'), 'families/personal/datasets/current')));
  const archived = query(collection(d, 'families'), where('owner', '==', 'wife'), where('status', '==', 'ARCHIVED'));
  assert.deepEqual((await getDocs(archived)).docs.map(it => it.id), ['personal']);
  await assertFails(getDocs(query(collection(db('owner'), 'families'), where('owner', '==', 'wife'), where('status', '==', 'ARCHIVED'))));
  assert.equal((await getDoc(doc(d, 'slots/1'))).data().familyId, 'personal');
  await assertFails(switchFamily(d));
});

test('archived workspace freezes uploads, devices, invitations, roles and status changes', async () => {
  await seedPersonal();
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/personal/invites/0'),
    { ...invitation('kid@example.com'), familyId: 'personal', inviter: 'wife', inviterEmail: 'wife@example.com' }));
  const d = db('wife');
  await assertSucceeds(switchFamily(d));
  await assertFails(uploadPersonal(d, 1));
  await assertFails(setDoc(doc(d, 'families/personal/installs/1'), { uid: 'wife', installId: 'other', name: 'Phone', createdAt: serverTimestamp() }));
  await assertFails(deleteDoc(doc(d, 'families/personal/installs/0')));
  await assertFails(setDoc(doc(d, 'families/personal/invites/1'),
    { ...invitation('kid@example.com'), familyId: 'personal', inviter: 'wife', inviterEmail: 'wife@example.com', createdAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(d, 'families/personal/invites/0'), { status: 'CANCELLED' }));
  await assertFails(updateDoc(doc(d, 'families/personal'), { status: 'ACTIVE', action: 'activate' }));
  await assertFails(updateDoc(doc(d, 'families/personal'), { status: 'DELETING', action: 'delete', deletionPhase: 0 }));
  const kid = db('kid');
  const join = writeBatch(kid);
  join.update(doc(kid, 'families/personal'), { members: { wife: 'm-wife', kid: 'm-kid' }, action: 'join', target: 'kid', inviteSlot: '0' });
  join.update(doc(kid, 'families/personal/invites/0'), { status: 'ACCEPTED', uid: 'kid' });
  join.set(doc(kid, 'users/kid'), { familyId: 'personal', memberId: 'm-kid', status: 'ACTIVE' });
  await assertFails(join.commit());
});

test('account pointer cannot switch without archiving and archiving cannot strand the account', async () => {
  await seedPersonal();
  const d = db('wife');
  await assertFails(switchFamily(d, { archive: false }));
  await assertFails(switchFamily(d, { join: false }));
  await assertFails(updateDoc(doc(d, 'families/personal'), { status: 'ARCHIVED', action: 'archive', target: 'family' }));
  await assertFails(setDoc(doc(d, 'users/wife'), { familyId: 'family', memberId: 'm-joined', status: 'ACTIVE' }));
  assert.equal(await personalStatus(), 'ACTIVE');
});

test('shared workspace cannot be archived by its owner and nobody else can archive a personal one', async () => {
  await seedPersonal({ wife: 'm-wife', kid: 'm-kid' });
  await assertFails(switchFamily(db('wife')));
  assert.equal(await personalStatus(), 'ACTIVE');
  await env.clearFirestore();
  await seedPersonal();
  await assertFails(updateDoc(doc(db('owner'), 'families/personal'), { status: 'ARCHIVED', action: 'archive', target: 'family' }));
  assert.equal(await personalStatus(), 'ACTIVE');
});

test('expired or cancelled invitation leaves the personal workspace active and linked', async () => {
  await seedPersonal(undefined, Date.now() - 7 * 86400000);
  await assertFails(switchFamily(db('wife')));
  assert.equal(await personalStatus(), 'ACTIVE');
  await env.clearFirestore();
  await seedPersonal();
  await assertSucceeds(updateDoc(doc(db('owner'), 'families/family/invites/0'), { status: 'CANCELLED' }));
  await assertFails(switchFamily(db('wife')));
  assert.equal(await personalStatus(), 'ACTIVE');
  assert.equal((await getDoc(doc(db('wife'), 'users/wife'))).data().familyId, 'personal');
});
