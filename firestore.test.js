const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123456";
const BOB_UID = "bob_654321";

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

test("Unauthenticated user: cannot read users", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").get());
});

test("Authenticated user: can create their own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      sixDigitId: "123456",
      username: "alice_wonder",
      displayName: "Alice",
      about: "Hello from ChatConnect",
      avatarUrl: "https://example.com/avatar.png",
      passwordHash: "hash123",
      online: true,
      lastSeen: new Date(),
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("Authenticated user: cannot create profile with someone else's userId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      sixDigitId: "654321",
      username: "bob_builder",
      displayName: "Bob",
      createdAt: new Date(),
    })
  );
});

test("Authenticated user: can query conversations they belong to", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("conversations").doc("conv_1").set({
      conversationId: "conv_1",
      isGroup: false,
      creatorId: ALICE_UID,
      participantIds: [ALICE_UID, BOB_UID],
      createdAt: new Date(),
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("conversations").where("participantIds", "array-contains", ALICE_UID).get()
  );
});

test("Non-participant: cannot read conversation or messages", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("conversations").doc("secret_conv").set({
      conversationId: "secret_conv",
      isGroup: true,
      creatorId: BOB_UID,
      participantIds: [BOB_UID],
      createdAt: new Date(),
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("conversations").doc("secret_conv").get());
});

test("Authenticated user: can check non-existent conversation without permission error", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("conversations").doc("direct_alice_bob_new").get());
});

test("Authenticated user: can create a direct conversation where they are participant", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("conversations").doc("direct_alice_bob_new").set({
      conversationId: "direct_alice_bob_new",
      isGroup: false,
      name: "Bob",
      creatorId: ALICE_UID,
      participantIds: [ALICE_UID, BOB_UID],
      createdAt: new Date(),
    })
  );
});

test("Authenticated user: can start call signaling as caller", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_test_1").set({
      callId: "call_test_1",
      callerId: ALICE_UID,
      callerName: "Alice",
      receiverId: BOB_UID,
      receiverName: "Bob",
      callType: "audio",
      status: "calling",
      createdAt: new Date(),
    })
  );
});
