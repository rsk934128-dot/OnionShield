const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

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

test("Unauthenticated user: cannot list or write synced_links, session_monitors, or session_rounds", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").set({
      userId: ALICE_UID,
      title: "Wikipedia",
      url: "https://en.wikipedia.org",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("session_monitors").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("session_rounds").get());
});

test("Authenticated owner: can create, list, update, and delete valid synced link", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_wiki");
  await assertSucceeds(
    ref.set({
      userId: ALICE_UID,
      title: "Wikipedia",
      url: "https://en.wikipedia.org",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertSucceeds(
    ref.update({
      title: "Wikipedia Home App",
      kind: "home_app",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(ref.delete());

  const histRef = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("hist_1");
  await assertSucceeds(
    histRef.set({
      userId: ALICE_UID,
      title: "Tor Project",
      url: "https://www.torproject.org",
      kind: "history",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_kind").set({
      userId: ALICE_UID,
      title: "Invalid",
      url: "https://example.org",
      kind: "password",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(histRef.delete());
});

test("Cross-user isolation: Bob cannot read or overwrite Alice's synced links", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const aliceRef = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1");
  await assertSucceeds(
    aliceRef.set({
      userId: ALICE_UID,
      title: "DuckDuckGo",
      url: "https://duckduckgo.com",
      kind: "home_app",
      createdAt: now,
      updatedAt: now,
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").delete());
});

test("Shadow update & schema validation: rejects ghost fields and invalid schemes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_ghost").set({
      userId: ALICE_UID,
      title: "Test",
      url: "https://example.org",
      kind: "bookmark",
      isVerified: true,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_scheme").set({
      userId: ALICE_UID,
      title: "XSS",
      url: "javascript:alert(1)",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Session Monitor & Rounds: owner CRUD succeeds, cross-user and invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  const monitorRef = aliceDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("active_session");
  await assertSucceeds(
    monitorRef.set({
      userId: ALICE_UID,
      siteHost: "example.org",
      guardEnabled: true,
      alertLevel: "SAFE",
      elapsedMinutes: 12,
      timeLimitMinutes: 30,
      budgetLimit: 100.0,
      currencyLabel: "BDT",
      totalRounds: 4,
      winsCount: 2,
      totalWagered: 20.0,
      totalReturned: 28.5,
      netProfit: 8.5,
      averageCrashMultiplier: 2.35,
      maxCrashMultiplier: 5.71,
      isMonitoringActive: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    monitorRef.update({
      elapsedMinutes: 15,
      alertLevel: "APPROACHING_LIMIT",
      updatedAt: new Date(),
    })
  );

  // Reject mutating immutable userId on update
  await assertFails(
    monitorRef.update({
      userId: BOB_UID,
      updatedAt: new Date(),
    })
  );

  // Reject invalid alertLevel and winsCount > totalRounds
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("bad_mon").set({
      userId: ALICE_UID,
      siteHost: "example.org",
      guardEnabled: true,
      alertLevel: "PREDICTION",
      elapsedMinutes: 0,
      timeLimitMinutes: 30,
      budgetLimit: 100.0,
      currencyLabel: "BDT",
      totalRounds: 1,
      winsCount: 5,
      totalWagered: 10.0,
      totalReturned: 0.0,
      netProfit: -10.0,
      averageCrashMultiplier: 1.2,
      maxCrashMultiplier: 1.2,
      isMonitoringActive: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Cross-user isolation on session_monitors
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("active_session").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_monitors").get());

  // Session rounds CRUD & validation
  const roundRef = aliceDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_101");
  await assertSucceeds(
    roundRef.set({
      userId: ALICE_UID,
      siteHost: "example.org",
      betAmount: 10.0,
      crashMultiplier: 5.71,
      cashoutMultiplier: 2.0,
      netProfit: 10.0,
      note: "Logged test",
      clientTimestampMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_101").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_bad").set({
      userId: ALICE_UID,
      siteHost: "example.org",
      betAmount: -5.0,
      crashMultiplier: 0.5,
      cashoutMultiplier: 0.0,
      netProfit: 0.0,
      note: "",
      clientTimestampMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(roundRef.delete());
});

test("Admin Config & Maintenance Logs: unauthenticated, non-admin, and unverified spoof denied; verified & registry admin allowed", async () => {
  const ADMIN_UID = "admin_uid_001";
  const BOOTSTRAP_UID = "bootstrap_admin_uid";
  const now = new Date();

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const spoofAdminDb = testEnv.authenticatedContext("spoof_uid", { email: "monikhoku88@gmail.com", email_verified: false }).firestore();
  const bootstrapAdminDb = testEnv.authenticatedContext(BOOTSTRAP_UID, { email: "monikhoku88@gmail.com", email_verified: true }).firestore();
  const registryAdminDb = testEnv.authenticatedContext(ADMIN_UID, { email: "secops@onionshield.org", email_verified: true }).firestore();

  // Seed /admins/{ADMIN_UID} via admin context
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      email: "secops@onionshield.org",
      role: "admin",
      createdAt: now,
    });
  });

  // 1. Unauthenticated access denied
  await assertFails(unauthDb.collection("admin_config").doc("global_settings").get());
  await assertFails(unauthDb.collection("maintenance_logs").get());

  // 2. Non-admin cannot self-promote or access admin collections
  await assertFails(
    aliceDb.collection("admins").doc(ALICE_UID).set({
      uid: ALICE_UID,
      email: "alice@example.com",
      role: "admin",
      createdAt: now,
    })
  );
  await assertFails(aliceDb.collection("admin_config").doc("global_settings").get());
  await assertFails(
    aliceDb.collection("admin_config").doc("global_settings").set({
      maintenanceMode: true,
      strictTorRequired: false,
      remoteTelemetryEnabled: true,
      forceAdBlockSync: true,
      emergencyLockdown: false,
      maintenanceBanner: "Hijack attempt",
      updatedByUid: ALICE_UID,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(aliceDb.collection("maintenance_logs").get());

  // 3. Unverified email spoof must fail even with bootstrapped email
  await assertFails(spoofAdminDb.collection("admin_config").doc("global_settings").get());
  await assertFails(
    spoofAdminDb.collection("maintenance_logs").doc("log_spoof").set({
      action: "Spoof log",
      category: "SECURITY",
      severity: "WARN",
      details: "Should fail",
      actorUid: "spoof_uid",
      actorEmail: "monikhoku88@gmail.com",
      clientTimestampMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );

  // 4. Verified bootstrapped admin can create & update admin_config
  const configRef = bootstrapAdminDb.collection("admin_config").doc("global_settings");
  await assertSucceeds(
    configRef.set({
      maintenanceMode: false,
      strictTorRequired: true,
      remoteTelemetryEnabled: true,
      forceAdBlockSync: true,
      emergencyLockdown: false,
      maintenanceBanner: "All privacy relays operational.",
      updatedByUid: BOOTSTRAP_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // 5. DB-registry admin can read and update admin_config, cannot delete
  await assertSucceeds(registryAdminDb.collection("admin_config").doc("global_settings").get());
  await assertSucceeds(
    registryAdminDb.collection("admin_config").doc("global_settings").update({
      maintenanceMode: true,
      maintenanceBanner: "Scheduled Tor bridge rotation.",
      updatedByUid: ADMIN_UID,
      updatedAt: new Date(),
    })
  );
  await assertFails(registryAdminDb.collection("admin_config").doc("global_settings").delete());

  // 6. Registry admin can create, list, update, and delete maintenance logs; bad category rejected
  const logRef = registryAdminDb.collection("maintenance_logs").doc("log_001");
  await assertSucceeds(
    logRef.set({
      action: "Enabled Maintenance Mode",
      category: "MAINTENANCE",
      severity: "WARN",
      details: "Rotating v3 onion directory mirrors.",
      actorUid: ADMIN_UID,
      actorEmail: "secops@onionshield.org",
      clientTimestampMs: 1700000001000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(registryAdminDb.collection("maintenance_logs").orderBy("createdAt", "desc").get());
  await assertSucceeds(
    logRef.update({
      details: "Mirror rotation complete.",
      severity: "INFO",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    registryAdminDb.collection("maintenance_logs").doc("log_bad").set({
      action: "Bad",
      category: "UNKNOWN",
      severity: "INFO",
      details: "",
      actorUid: ADMIN_UID,
      actorEmail: "secops@onionshield.org",
      clientTimestampMs: 1700000002000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(logRef.delete());
});

test("Custom 'admin' claim: token.admin=true grants access while admin=false is denied", async () => {
  const CLAIM_ADMIN_UID = "claim_admin_uid";
  const FALSE_CLAIM_UID = "false_claim_uid";
  const now = new Date();

  const claimAdminDb = testEnv.authenticatedContext(CLAIM_ADMIN_UID, {
    email: "ops-claim@onionshield.org",
    email_verified: true,
    admin: true,
  }).firestore();

  const falseClaimDb = testEnv.authenticatedContext(FALSE_CLAIM_UID, {
    email: "viewer@onionshield.org",
    email_verified: true,
    admin: false,
  }).firestore();

  // Signed-in user with admin=false claim must be denied
  await assertFails(falseClaimDb.collection("admin_config").doc("global_settings").get());
  await assertFails(falseClaimDb.collection("maintenance_logs").get());
  await assertFails(
    falseClaimDb.collection("admin_config").doc("global_settings").set({
      maintenanceMode: true,
      strictTorRequired: true,
      remoteTelemetryEnabled: false,
      forceAdBlockSync: true,
      emergencyLockdown: true,
      maintenanceBanner: "Unauthorized attempt",
      updatedByUid: FALSE_CLAIM_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // User with custom 'admin' claim (admin: true) can create/update server privacy overrides and maintenance logs
  const configRef = claimAdminDb.collection("admin_config").doc("global_settings");
  await assertSucceeds(
    configRef.set({
      maintenanceMode: false,
      strictTorRequired: true,
      remoteTelemetryEnabled: false,
      forceAdBlockSync: true,
      emergencyLockdown: true,
      maintenanceBanner: "Emergency privacy override active.",
      updatedByUid: CLAIM_ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(configRef.get());
  await assertSucceeds(
    configRef.update({
      emergencyLockdown: false,
      updatedByUid: CLAIM_ADMIN_UID,
      updatedAt: new Date(),
    })
  );

  const claimLogRef = claimAdminDb.collection("maintenance_logs").doc("log_claim_001");
  await assertSucceeds(
    claimLogRef.set({
      action: "Toggled Server-Side Privacy Override",
      category: "SECURITY",
      severity: "CRITICAL",
      details: "Enabled emergency lockdown via custom admin claim session.",
      actorUid: CLAIM_ADMIN_UID,
      actorEmail: "ops-claim@onionshield.org",
      clientTimestampMs: 1700000010000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(claimAdminDb.collection("maintenance_logs").orderBy("createdAt", "desc").get());
  await assertSucceeds(claimLogRef.delete());
});

