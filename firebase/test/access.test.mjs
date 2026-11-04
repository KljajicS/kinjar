import { after, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, writeBatch, Bytes, Timestamp, serverTimestamp, setLogLevel, onSnapshot } from 'firebase/firestore';

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
const state = () => ({ count: 0, bytes: 0, day: Math.floor(Date.now() / 86400000), dailyCount: 0, lastOpId: '' });
const initialState = () => ({ ...state(), chunks: 0, chunkBytes: 0, expectedChunks: 1,
  expectedBytes: 3, digest: '0'.repeat(64), lastChunk: '', sealed: false });
async function seed(members, overrides = {}) {
  await env.withSecurityRulesDisabled(async context => {
    const d = context.firestore();
    await setDoc(doc(d, 'families/family'), { ...family(members), ...overrides });
    await setDoc(doc(d, 'slots/0'), { familyId: 'family', owner: 'owner' });
    await setDoc(doc(d, 'families/family/datasets/current'), state());
    await setDoc(doc(d, 'families/family/installs/0'), { uid: 'owner', installId: 'device' });
    for (const [uid, memberId] of Object.entries(members ?? { owner: 'm-owner' })) {
      await setDoc(doc(d, `users/${uid}`), { familyId: 'family', memberId, status: 'ACTIVE' });
    }
  });
}
function operation() {
  return { uid: 'owner', installId: 'device', installSlot: '0', sequence: 1,
    payload: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])), createdAt: serverTimestamp() };
}
function upload(d, options = {}) {
  const batch = writeBatch(d);
  batch.set(doc(d, 'families/family/datasets/current/operations/device:1'), { ...operation(), ...options.operation });
  batch.update(doc(d, 'families/family/datasets/current'), { ...state(), count: 1, bytes: 3, dailyCount: 1,
    lastOpId: 'device:1', ...options.state });
  return batch;
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
    expiresAt: Timestamp.fromMillis(Date.now() + 3600000),
  }));
  await assertSucceeds(getDoc(doc(db('wife'), 'families/family')));
  await assertFails(getDoc(doc(db('wife'), 'families/family/datasets/current')));
});
test('invitation acceptance requires membership and user reference in same commit', async () => {
  await seed();
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/invites/0'), {
    familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com', status: 'PENDING', uid: '',
    expiresAt: Timestamp.fromMillis(Date.now() + 3600000),
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

test('family creation reserves one public slot and links account atomically', async () => {
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
test('ordinary users cannot claim a slot or forge family reference separately', async () => {
  const d = db('owner');
  await assertFails(setDoc(doc(d, 'slots/0'), { familyId: 'family', owner: 'owner' }));
  await assertFails(setDoc(doc(d, 'users/owner'), { familyId: 'family', memberId: 'm-owner', status: 'ACTIVE' }));
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
test('state caps bound both document count and payload bytes', async () => {
  await seed();
  const d = db('owner');
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/current'), { ...state(), count: 1000 }));
  await assertFails(upload(d, { state: { count: 1001 } }).commit());
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/current'), { ...state(), bytes: 10485760 }));
  await assertFails(upload(d, { state: { bytes: 10485763 } }).commit());
});
test('daily usage resets only on server day change', async () => {
  await seed();
  const yesterday = state().day - 1;
  await env.withSecurityRulesDisabled(async c => setDoc(doc(c.firestore(), 'families/family/datasets/current'), { ...state(), day: yesterday, dailyCount: 200 }));
  await assertFails(upload(db('owner'), { state: { day: yesterday, dailyCount: 1 } }).commit());
  await assertSucceeds(upload(db('owner')).commit());
});
test('invitation creation is bounded and cannot name another inviter', async () => {
  await seed();
  const d = db('owner');
  const invite = { familyId: 'family', email: 'wife@example.com', inviter: 'owner', inviterEmail: 'owner@example.com', status: 'PENDING', uid: '',
    expiresAt: Timestamp.fromMillis(Date.now() + 3600000) };
  await assertSucceeds(setDoc(doc(d, 'families/family/invites/0'), invite));
  await assertFails(setDoc(doc(d, 'families/family/invites/6'), invite));
  await assertFails(setDoc(doc(d, 'families/family/invites/1'), { ...invite, inviter: 'stranger' }));
  await assertFails(setDoc(doc(d, 'families/family/invites/1'), { ...invite, expiresAt: Timestamp.fromMillis(Date.now() + 9 * 86400000) }));
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
    otherDataset: target, restoreToken: token, restoreUid: 'owner', restoreExpires: Timestamp.fromMillis(Date.now() + 600000) });
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
test('expired restore cannot activate and administrators can cancel it', async () => {
  await seed();
  const d = db('owner');
  await assertSucceeds(startRestore(d));
  await assertSucceeds(stageRestore(d));
  await env.withSecurityRulesDisabled(async c => updateDoc(doc(c.firestore(), 'families/family'), { restoreExpires: Timestamp.fromMillis(0) }));
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
  await env.withSecurityRulesDisabled(async c => updateDoc(doc(c.firestore(), 'families/family'), { restoreExpires: Timestamp.fromMillis(0) }));
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
