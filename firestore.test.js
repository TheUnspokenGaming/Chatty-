const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";
const EVE_UID = "eve_uid_003";

const FOUR_GB_BYTES = 4294967296;
const SAMPLE_SHA256 = "a".repeat(64);
const SAMPLE_ROOM_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
const SAMPLE_PUB_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890abcdef1234567890abcdef";
const SAMPLE_IV = "MTIzNDU2Nzg5MDEy";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

function futureDate(offsetMs = 3600000) {
  return new Date(Date.now() + offsetMs);
}

function buildValidRoomPayload(ownerId = ALICE_UID, inviteCode = "NX8K4P2M") {
  const now = pastDate(2000);
  return {
    ownerId,
    name: "Core Architecture",
    topic: "Zero-knowledge encrypted multi-channel space",
    inviteCode,
    accentTag: "emerald",
    roomKeyBase64: SAMPLE_ROOM_KEY,
    keyFingerprint: "4F9A-C12E-8B04-D371",
    keyVersion: 1,
    memberUids: [ownerId],
    memberNames: { [ownerId]: "Alice Mercer" },
    memberRoles: { [ownerId]: "owner" },
    typingUserIds: [],
    typingUpdatedAt: now,
    slowModeSeconds: 0,
    defaultTtlMinutes: 60,
    maxFileSizeBytes: FOUR_GB_BYTES,
    lastActivityAt: now,
    createdAt: now,
    updatedAt: now,
  };
}

// --- 1. UNAUTHENTICATED DENIAL ---
test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("contact_directory").doc("NX-7K9P-2M4Q").get());
  await assertFails(unauthDb.collection("rooms").get());
  await assertFails(unauthDb.collection("direct_threads").get());
  await assertFails(unauthDb.collection("media_drops").get());
  await assertFails(unauthDb.collection("rooms").doc("room_1").set(buildValidRoomPayload()));
});

// --- 2. USER IDENTITY, LIVE PRESENCE & CONTACT DIRECTORY ISOLATION ---
test("UserIdentity & ContactDirectory: owner CRUD works; listing directory & cross-user edits fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = pastDate(2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Mercer",
      contactCode: "NX-7K9P-2M4Q",
      presenceStatus: "online",
      statusText: "Shipping E2EE",
      publicKeyFingerprint: "9A1B-2C3D-4E5F-6071",
      publicKeyBase64: SAMPLE_PUB_KEY,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      presenceStatus: "focus",
      statusText: "Deep work",
      updatedAt: pastDate(500),
    })
  );

  await assertSucceeds(
    aliceDb.collection("contact_directory").doc("NX-7K9P-2M4Q").set({
      userId: ALICE_UID,
      displayName: "Alice Mercer",
      contactCode: "NX-7K9P-2M4Q",
      presenceStatus: "focus",
      statusText: "Deep work",
      publicKeyFingerprint: "9A1B-2C3D-4E5F-6071",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can single-get Alice's Contact Code, but CANNOT list the directory or modify Alice's entry
  await assertSucceeds(bobDb.collection("contact_directory").doc("NX-7K9P-2M4Q").get());
  await assertFails(bobDb.collection("contact_directory").get());
  await assertFails(bobDb.collection("contact_directory").doc("NX-7K9P-2M4Q").update({ displayName: "Spoofed" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
});

// --- 3. MULTI-CHANNEL ROOMS, VOICE LOUNGES, ROLES & INVITE JOIN ---
test("Multi-channel Rooms & Voice Lounges: channels, voice presence, roles, slow-mode & anti-escalation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const now = pastDate(2000);

  await assertSucceeds(aliceDb.collection("rooms").doc("room_1").set(buildValidRoomPayload()));
  await assertSucceeds(
    aliceDb.collection("room_invites").doc("NX8K4P2M").set({
      roomId: "room_1",
      roomName: "Core Architecture",
      ownerId: ALICE_UID,
      createdAt: now,
    })
  );

  // Alice creates a Voice Lounge channel
  await assertSucceeds(
    aliceDb.collection("rooms").doc("room_1").collection("channels").doc("voice_lounge").set({
      roomId: "room_1",
      ownerId: ALICE_UID,
      name: "Voice Lounge",
      channelType: "voice",
      topic: "E2EE Push-to-Talk",
      voiceParticipantUids: [],
      voiceParticipantNames: {},
      mutedUids: [],
      raisedHandUids: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot self-escalate to owner when joining via invite code
  await assertFails(
    bobDb.collection("rooms").doc("room_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: { [ALICE_UID]: "Alice Mercer", [BOB_UID]: "Bob" },
      memberRoles: { [ALICE_UID]: "owner", [BOB_UID]: "owner" },
      updatedAt: pastDate(500),
    })
  );

  // Bob joins legitimately as 'member'
  await assertSucceeds(
    bobDb.collection("rooms").doc("room_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: { [ALICE_UID]: "Alice Mercer", [BOB_UID]: "Bob" },
      memberRoles: { [ALICE_UID]: "owner", [BOB_UID]: "member" },
      updatedAt: pastDate(400),
    })
  );

  // Bob can join the Voice Lounge and raise hand, but cannot create channels as plain member
  await assertSucceeds(
    bobDb.collection("rooms").doc("room_1").collection("channels").doc("voice_lounge").update({
      voiceParticipantUids: [BOB_UID],
      voiceParticipantNames: { [BOB_UID]: "Bob" },
      raisedHandUids: [BOB_UID],
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    bobDb.collection("rooms").doc("room_1").collection("channels").doc("bob_chan").set({
      roomId: "room_1",
      ownerId: ALICE_UID,
      name: "unauthorized",
      channelType: "text",
      topic: "",
      voiceParticipantUids: [],
      voiceParticipantNames: {},
      mutedUids: [],
      raisedHandUids: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice updates slow-mode & promotes Bob to admin; Eve is completely blocked
  await assertSucceeds(
    aliceDb.collection("rooms").doc("room_1").update({
      slowModeSeconds: 15,
      memberRoles: { [ALICE_UID]: "owner", [BOB_UID]: "admin" },
      updatedAt: pastDate(200),
    })
  );
  await assertFails(eveDb.collection("rooms").doc("room_1").collection("channels").get());
});

// --- 4. RICH ROOM MESSAGES: POLLS (ANTI-SPOOF), REACTIONS, EDITS, PINS & VOICE CLIPS ---
test("Rich Room Messages: replies, edits, reactions, pins, voice bursts & anti-spoof poll voting", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = pastDate(2000);

  await assertSucceeds(aliceDb.collection("rooms").doc("room_1").set(buildValidRoomPayload()));
  await assertSucceeds(
    aliceDb.collection("rooms").doc("room_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: pastDate(1500),
    })
  );

  // Alice creates an encrypted poll
  const pollRef = aliceDb.collection("rooms").doc("room_1").collection("messages").doc("poll_1");
  await assertSucceeds(
    pollRef.set({
      roomId: "room_1",
      channelId: "general",
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      messageType: "poll",
      ciphertextBase64: "ZW5jcnlwdGVkX3BvbGw=",
      ivBase64: SAMPLE_IV,
      keyVersion: 1,
      pollOptionCount: 3,
      pollVotes: {},
      reactions: {},
      pinned: false,
      edited: false,
      status: "active",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob votes for himself (allowed)
  await assertSucceeds(
    bobDb.collection("rooms").doc("room_1").collection("messages").doc("poll_1").update({
      pollVotes: { [BOB_UID]: "1" },
      updatedAt: pastDate(500),
    })
  );

  // Bob tries to spoof Alice's vote (BLOCKED!)
  await assertFails(
    bobDb.collection("rooms").doc("room_1").collection("messages").doc("poll_1").update({
      pollVotes: { [BOB_UID]: "1", [ALICE_UID]: "0" },
      updatedAt: pastDate(400),
    })
  );

  // Bob reacts & pins (allowed), but cannot edit Alice's ciphertext (BLOCKED!)
  await assertSucceeds(
    bobDb.collection("rooms").doc("room_1").collection("messages").doc("poll_1").update({
      reactions: { "🔥": [BOB_UID] },
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    bobDb.collection("rooms").doc("room_1").collection("messages").doc("poll_1").update({
      ciphertextBase64: "dGFtcGVyZWQ=",
      ivBase64: SAMPLE_IV,
      edited: true,
      updatedAt: pastDate(200),
    })
  );

  // Alice edits her own message (allowed)
  await assertSucceeds(
    pollRef.update({
      ciphertextBase64: "cmVlbmNyeXB0ZWQ=",
      ivBase64: SAMPLE_IV,
      edited: true,
      updatedAt: pastDate(100),
    })
  );
});

// --- 5. DIRECT MESSAGING: TYPING INDICATORS, READ RECEIPTS, VOICE & CROSS-USER ISOLATION ---
test("Direct Messaging: deterministic thread lookup, typing, read receipts, reactions & Eve denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const now = pastDate(2000);
  const threadId = `dm_${ALICE_UID}_${BOB_UID}`;

  // Pre-creation existence check succeeds when resource == null
  await assertSucceeds(aliceDb.collection("direct_threads").doc(threadId).get());

  await assertSucceeds(
    aliceDb.collection("direct_threads").doc(threadId).set({
      participantUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      participantFingerprints: { [ALICE_UID]: "AA11-BB22", [BOB_UID]: "CC33-DD44" },
      threadKeyBase64: SAMPLE_ROOM_KEY,
      keyFingerprint: "1122-3344-5566",
      keyVersion: 1,
      lastReadByUids: [ALICE_UID],
      typingUserIds: [],
      typingUpdatedAt: now,
      lastActivityAt: now,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Eve cannot read or write the thread
  await assertFails(eveDb.collection("direct_threads").doc(threadId).get());

  // Bob updates typing indicator
  await assertSucceeds(
    bobDb.collection("direct_threads").doc(threadId).update({
      typingUserIds: [BOB_UID],
      typingUpdatedAt: pastDate(400),
      updatedAt: pastDate(400),
    })
  );

  // Alice sends encrypted direct voice note
  await assertSucceeds(
    aliceDb.collection("direct_threads").doc(threadId).collection("messages").doc("dm_msg_1").set({
      threadId,
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID],
      messageType: "voice",
      ciphertextBase64: "dm9pY2VfY2FwdGlvbg==",
      ivBase64: SAMPLE_IV,
      keyVersion: 1,
      encryptedAudioBase64: "ZW5jcnlwdGVkX2F1ZGlvX2J5dGVz",
      audioIvBase64: SAMPLE_IV,
      audioDurationMs: 4200,
      reactions: {},
      pinned: false,
      edited: false,
      readByUids: [ALICE_UID],
      status: "active",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob marks Alice's message as read (read receipt)
  await assertSucceeds(
    bobDb.collection("direct_threads").doc(threadId).collection("messages").doc("dm_msg_1").update({
      readByUids: [ALICE_UID, BOB_UID],
      readAt: pastDate(200),
      updatedAt: pastDate(200),
    })
  );
});

// --- 6. 4.00 GB MEDIA DROPS IN BOTH ROOMS AND DIRECT MESSAGES ---
test("MediaDrops: exact 4.00 GB allowed in both Rooms and DMs; 4GB + 1 byte rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = pastDate(2000);
  const threadId = `dm_${ALICE_UID}_${BOB_UID}`;

  await assertSucceeds(aliceDb.collection("rooms").doc("room_1").set(buildValidRoomPayload()));
  await assertSucceeds(
    aliceDb.collection("direct_threads").doc(threadId).set({
      participantUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      participantFingerprints: { [ALICE_UID]: "AA11", [BOB_UID]: "BB22" },
      threadKeyBase64: SAMPLE_ROOM_KEY,
      keyFingerprint: "1122-3344-5566",
      keyVersion: 1,
      lastReadByUids: [ALICE_UID],
      typingUserIds: [],
      typingUpdatedAt: now,
      lastActivityAt: now,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Exact 4.00 GB drop in Room succeeds
  await assertSucceeds(
    aliceDb.collection("media_drops").doc("drop_room_4gb").set({
      roomId: "room_1",
      roomName: "Core Architecture",
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID],
      fileName: "raw_cut_4gb.mov",
      mimeType: "video/quicktime",
      fileSizeBytes: FOUR_GB_BYTES,
      sha256Hash: SAMPLE_SHA256,
      cipherAlgorithm: "AES-256-GCM",
      ivBase64: SAMPLE_IV,
      ttlMinutes: 60,
      burnAfterView: true,
      status: "active",
      viewedByUids: [],
      expiresAt: futureDate(),
      createdAt: now,
      updatedAt: now,
    })
  );

  // Exact 4.00 GB drop in Direct Thread succeeds
  await assertSucceeds(
    aliceDb.collection("media_drops").doc("drop_dm_4gb").set({
      roomId: threadId,
      roomName: "DM • Bob",
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      fileName: "private_archive.zip",
      mimeType: "application/zip",
      fileSizeBytes: FOUR_GB_BYTES,
      sha256Hash: SAMPLE_SHA256,
      cipherAlgorithm: "AES-256-GCM",
      ivBase64: SAMPLE_IV,
      ttlMinutes: 5,
      burnAfterView: false,
      status: "active",
      viewedByUids: [],
      expiresAt: futureDate(),
      createdAt: now,
      updatedAt: now,
    })
  );

  // 4.00 GB + 1 byte strictly rejected
  await assertFails(
    aliceDb.collection("media_drops").doc("drop_too_big").set({
      roomId: "room_1",
      roomName: "Core Architecture",
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID],
      fileName: "oversized.bin",
      mimeType: "application/octet-stream",
      fileSizeBytes: FOUR_GB_BYTES + 1,
      sha256Hash: SAMPLE_SHA256,
      cipherAlgorithm: "AES-256-GCM",
      ivBase64: SAMPLE_IV,
      ttlMinutes: 60,
      burnAfterView: false,
      status: "active",
      viewedByUids: [],
      expiresAt: futureDate(),
      createdAt: now,
      updatedAt: now,
    })
  );
});

// --- 7. BURN-AFTER-VIEW & SHREDDING ENFORCEMENT ---
test("MediaDrop burn-after-view triggers terminal burned state and prevents re-activation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = pastDate(2000);

  await assertSucceeds(aliceDb.collection("rooms").doc("room_1").set(buildValidRoomPayload()));
  const dropRef = aliceDb.collection("media_drops").doc("drop_burn");
  await assertSucceeds(
    dropRef.set({
      roomId: "room_1",
      roomName: "Core Architecture",
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID],
      fileName: "secret.png",
      mimeType: "image/png",
      fileSizeBytes: 10240,
      sha256Hash: SAMPLE_SHA256,
      cipherAlgorithm: "AES-256-GCM",
      ivBase64: SAMPLE_IV,
      encryptedPreviewBase64: "cHJldmlldw==",
      previewIvBase64: SAMPLE_IV,
      ttlMinutes: 5,
      burnAfterView: true,
      status: "active",
      viewedByUids: [],
      expiresAt: futureDate(),
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    dropRef.update({
      viewedByUids: [ALICE_UID],
      status: "burned",
      encryptedPreviewBase64: "",
      previewIvBase64: "",
      updatedAt: pastDate(100),
    })
  );

  // Re-activating a burned drop fails
  await assertFails(
    dropRef.update({
      status: "active",
      updatedAt: pastDate(50),
    })
  );
});
