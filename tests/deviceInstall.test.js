import assert from "node:assert/strict";
import test from "node:test";
import { connectedSerials, packageUpdateTime, parseSessionId, sessionCreateArguments, shellInstallArguments } from "../lib/deviceInstall.js";

test("opens the system installer with Play Store attribution", () => {
  const args = shellInstallArguments("/data/local/tmp/automaker-1.apk");
  assert.deepEqual(args.slice(0, 4), ["shell", "am", "start", "-a"]);
  assert.ok(args.includes("android.intent.action.INSTALL_PACKAGE"));
  assert.ok(args.includes("file:///data/local/tmp/automaker-1.apk"));
  assert.ok(args.includes("com.android.vending"));
  assert.ok(args.includes("android-app://com.android.vending"));
  assert.equal(args.filter((arg) => arg === "com.android.vending").length, 1);
});

test("creates a Play Store install session for a split bundle", () => {
  const modern = sessionCreateArguments(34);
  assert.ok(modern.includes("install-create"));
  assert.ok(modern.includes("com.android.vending"));
  assert.ok(modern.includes("--originating-uri"));
  assert.deepEqual(sessionCreateArguments(33).slice(0, 7), ["shell", "pm", "install-create", "-r", "-i", "com.android.vending"]);
  assert.equal(parseSessionId("Success: created install session [1868135463]\n"), 1868135463);
  assert.equal(parseSessionId("Created session 42"), 42);
  assert.equal(parseSessionId("Failure"), null);
});

test("reads the package update time and ignores a missing package", () => {
  assert.equal(packageUpdateTime("Unable to find package: com.example.app"), null);
  assert.equal(packageUpdateTime("    lastUpdateTime=2026-10-20 21:30:00\n"), "2026-10-20 21:30:00");
});

test("reads connected adb devices and skips unauthorized ones", () => {
  const output = "List of devices attached\nR5CRB13YKZW\tdevice\nemulator-5554\tunauthorized\n\n";
  assert.deepEqual(connectedSerials(output), ["R5CRB13YKZW"]);
});
