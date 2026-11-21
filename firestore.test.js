const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "operator_alice_01";
const BOB_UID = "operator_bob_02";
const VALID_HASH_A = "a".repeat(64);
const VALID_HASH_B = "b".repeat(64);
const GENESIS_HASH = "0".repeat(64);

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validAuditPayload(uid, createdAt = new Date(Date.now() - 1000)) {
  return {
    userId: uid,
    operatorEmail: "alice@shopfloor.io",
    operatorBadgeId: "OP-4021",
    taskName: "CNC Chuck Jam Recovery",
    voiceIntent: "Clear jammed billet from chuck jaw to reject bin",
    plannerSource: "GEMINI_AI_LOGIC",
    pickCoordsMm: "(240, 210, 185)",
    placeCoordsMm: "(520, -160, 120)",
    targetSpeedMmS: 180.0,
    guardrailLatencyUs: 320,
    recoveryDurationMs: 18400,
    guardrailsPassed: true,
    urScriptHash: VALID_HASH_A,
    ros2GoalHash: VALID_HASH_B,
    previousHash: GENESIS_HASH,
    recordHash: "c".repeat(64),
    urScriptPreview: "def roboxr_recovery_task():\n  movel(p[0.24,0.21,0.185,0,3.14,0], a=0.8, v=0.18)\nend",
    createdAt,
  };
}

function validTaskSpecPayload(uid, createdAt = new Date(Date.now() - 5000)) {
  return {
    userId: uid,
    taskName: "Haas VF-2 Tending",
    voiceIntent: "Pick finished flange from vise and load inspection tray",
    pickX: 250.0,
    pickY: 220.0,
    pickZ: 180.0,
    placeX: 520.0,
    placeY: -180.0,
    placeZ: 110.0,
    targetSpeedMmS: 200.0,
    createdAt,
    updatedAt: createdAt,
  };
}

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

test("Unauthenticated users cannot read or write auditLogs or taskSpecs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("auditLogs").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("auditLogs")
      .doc("log_1")
      .set(validAuditPayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("taskSpecs").get()
  );
});

test("Authenticated operator can append valid immutable SHA-256 audit log and read own logs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("auditLogs")
    .doc("log_001");

  await assertSucceeds(ref.set(validAuditPayload(ALICE_UID)));
  await assertSucceeds(ref.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("auditLogs").get()
  );

  // Cryptographic audit logs must be immutable (no update or delete)
  await assertFails(ref.update({ taskName: "Tampered Task" }));
  await assertFails(ref.delete());
});

test("Audit log rejects unsafe speed (>250mm/s), failed guardrails, future timestamp, or ghost field", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = aliceDb.collection("users").doc(ALICE_UID).collection("auditLogs");

  await assertFails(
    col.doc("bad_speed").set({
      ...validAuditPayload(ALICE_UID),
      targetSpeedMmS: 300.0,
    })
  );

  await assertFails(
    col.doc("bad_guardrail").set({
      ...validAuditPayload(ALICE_UID),
      guardrailsPassed: false,
    })
  );

  await assertFails(
    col.doc("future_ts").set(
      validAuditPayload(ALICE_UID, new Date(Date.now() + 60000))
    )
  );

  await assertFails(
    col.doc("ghost_field").set({
      ...validAuditPayload(ALICE_UID),
      id: "ghost_field",
    })
  );
});

test("Cross-user isolation blocks Bob from reading or writing Alice's auditLogs and taskSpecs", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("auditLogs")
      .doc("log_001")
      .set(validAuditPayload(ALICE_UID));
    await adminDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("taskSpecs")
      .doc("spec_001")
      .set(validTaskSpecPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("auditLogs").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("auditLogs")
      .doc("log_002")
      .set(validAuditPayload(BOB_UID))
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("taskSpecs").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("taskSpecs")
      .doc("spec_001")
      .delete()
  );
});

test("TaskSpecs allow owner CRUD with partial updates and immutable createdAt", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("taskSpecs")
    .doc("spec_001");

  await assertSucceeds(ref.set(validTaskSpecPayload(ALICE_UID)));

  // Partial update of mutable fields succeeds
  await assertSucceeds(
    ref.update({
      taskName: "Haas VF-2 Rapid Recovery",
      targetSpeedMmS: 190.0,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Mutating immutable createdAt fails
  await assertFails(
    ref.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Owner can delete own preset
  await assertSucceeds(ref.delete());
});
