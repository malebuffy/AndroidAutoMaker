import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { assertSafeEntry, chooseBaseApk, extractZip, packZip, runXapkPipeline } from "../lib/xapk.js";
import { ensureKeystore, findJava, paths } from "../lib/tools.js";

test("picks the base APK listed in an XAPK manifest", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "xapk-choose-"));
  fs.mkdirSync(path.join(root, "Android", "obb"), { recursive: true });
  fs.writeFileSync(path.join(root, "config.arm64_v8a.apk"), "split");
  fs.writeFileSync(path.join(root, "com.example.plainapp.apk"), "base");
  fs.writeFileSync(path.join(root, "Android", "obb", "main.obb"), "obb");
  fs.writeFileSync(
    path.join(root, "manifest.json"),
    JSON.stringify({
      package_name: "com.example.plainapp",
      split_apks: [
        { file: "com.example.plainapp.apk", id: "base" },
        { file: "config.arm64_v8a.apk", id: "config.arm64_v8a" },
      ],
    }),
  );
  const base = chooseBaseApk(root);
  assert.equal(path.basename(base), "com.example.plainapp.apk");
});

test("skips Unity asset packs and ABI splits when the XAPK has no manifest", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "xapk-unity-"));
  fs.writeFileSync(path.join(root, "UnityDataAssetPack.apk"), Buffer.alloc(64));
  fs.writeFileSync(path.join(root, "config.arm64_v8a.apk"), "abi");
  fs.writeFileSync(path.join(root, "com.example.plainapp.apk"), "base");
  const base = chooseBaseApk(root);
  assert.equal(path.basename(base), "com.example.plainapp.apk");
});

test("rejects an XAPK entry that escapes the archive", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "xapk-slip-"));
  assert.throws(() => assertSafeEntry(root, "../outside.txt"), /unsafe path/);
  assert.throws(() => assertSafeEntry(root, "/etc/passwd"), /unsafe path/);
  assert.doesNotThrow(() => assertSafeEntry(root, "Android/obb/com.example/main.obb"));
});

const SDK = "C:\\Users\\Vasilis\\AppData\\Local\\Android\\Sdk";
const AAPT2 = path.join(SDK, "build-tools", "36.0.0", "aapt2.exe");
const ANDROID_JAR = path.join(SDK, "platforms", "android-36", "android.jar");

function exec(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { windowsHide: true, maxBuffer: 16 * 1024 * 1024 }, (error, stdout, stderr) => {
      if (error) {
        error.toolOutput = `${stdout || ""}${stderr || ""}`;
        reject(error);
        return;
      }
      resolve(`${stdout || ""}${stderr || ""}`);
    });
  });
}

async function buildSample(directory, java) {
  const manifest = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.plainapp" android:versionCode="1" android:versionName="1.0">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
    <application android:label="@string/app_name" android:appCategory="video" android:isGame="false" android:hasCode="false">
        <activity android:name="com.example.plainapp.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
`;
  fs.mkdirSync(path.join(directory, "res", "values"), { recursive: true });
  fs.writeFileSync(path.join(directory, "AndroidManifest.xml"), manifest);
  fs.writeFileSync(
    path.join(directory, "res", "values", "strings.xml"),
    `<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">Plain App</string></resources>\n`,
  );
  const compiled = path.join(directory, "compiled");
  fs.mkdirSync(compiled, { recursive: true });
  const unsigned = path.join(directory, "unsigned.apk");
  const signed = path.join(directory, "sample.apk");
  await exec(AAPT2, ["compile", "-o", compiled, path.join(directory, "res", "values", "strings.xml")]);
  await exec(AAPT2, [
    "link",
    "-o",
    unsigned,
    "-I",
    ANDROID_JAR,
    "--manifest",
    path.join(directory, "AndroidManifest.xml"),
    "--min-sdk-version",
    "24",
    "--target-sdk-version",
    "34",
    path.join(compiled, "values_strings.arsc.flat"),
  ]);
  await exec(java, [
    "-jar",
    paths.apksigner,
    "sign",
    "--ks",
    paths.keystore,
    "--ks-key-alias",
    "androiddebugkey",
    "--ks-pass",
    "pass:android",
    "--key-pass",
    "pass:android",
    "--out",
    signed,
    unsigned,
  ]);
  return signed;
}

test("patches an XAPK base APK and re-signs its splits", { timeout: 180000 }, async () => {
  assert.ok(fs.existsSync(AAPT2), "aapt2 is required to build the sample APK");
  const { java } = findJava();
  ensureKeystore();
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "xapk-pipe-"));
  const sample = await buildSample(directory, java);
  const bundle = path.join(directory, "bundle");
  fs.mkdirSync(path.join(bundle, "Android", "obb", "com.example.plainapp"), { recursive: true });
  fs.copyFileSync(sample, path.join(bundle, "com.example.plainapp.apk"));
  fs.copyFileSync(sample, path.join(bundle, "config.en.apk"));
  fs.writeFileSync(path.join(bundle, "Android", "obb", "com.example.plainapp", "main.obb"), "expansion");
  fs.writeFileSync(
    path.join(bundle, "manifest.json"),
    JSON.stringify({
      xapk_version: 2,
      package_name: "com.example.plainapp",
      name: "Plain App",
      split_apks: [
        { file: "com.example.plainapp.apk", id: "base" },
        { file: "config.en.apk", id: "config.en" },
      ],
    }),
  );
  const input = path.join(directory, "plain.xapk");
  packZip(bundle, input);
  const output = path.join(directory, "out", "signed.xapk");
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const result = await runXapkPipeline({ inputPath: input, outputPath: output, log: [] });
  assert.equal(result.format, "xapk");
  assert.equal(result.packageName, "com.example.plainapp");
  assert.ok(result.changes.some((change) => /game/i.test(change)));
  assert.ok(result.changes.some((change) => /split/i.test(change)));

  const unpacked = path.join(directory, "unpacked");
  extractZip(output, unpacked);
  assert.equal(fs.readFileSync(path.join(unpacked, "Android", "obb", "com.example.plainapp", "main.obb"), "utf8"), "expansion");
  const manifest = JSON.parse(fs.readFileSync(path.join(unpacked, "manifest.json"), "utf8"));
  assert.equal(manifest.split_apks[0].id, "base");
  assert.ok(manifest.total_size > 0);

  const decoded = path.join(directory, "check");
  await exec(java, [
    "-jar",
    paths.apktool,
    "d",
    "-f",
    "-s",
    "-p",
    paths.framework,
    "-o",
    decoded,
    path.join(unpacked, "com.example.plainapp.apk"),
  ]);
  const patched = fs.readFileSync(path.join(decoded, "AndroidManifest.xml"), "utf8");
  assert.match(patched, /android:appCategory="game"/);
  assert.match(patched, /android.intent.category.CAR_LAUNCHER/);
  await exec(java, ["-jar", paths.apksigner, "verify", path.join(unpacked, "com.example.plainapp.apk")]);
  await exec(java, ["-jar", paths.apksigner, "verify", path.join(unpacked, "config.en.apk")]);
});
