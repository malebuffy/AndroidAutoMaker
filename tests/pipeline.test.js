import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { runPipeline } from "../lib/pipeline.js";
import { ensureKeystore, findJava, paths } from "../lib/tools.js";

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

test("round-trips a real APK into a signed parked game", { timeout: 180000 }, async () => {
  assert.ok(fs.existsSync(AAPT2), "aapt2 is required to build the sample APK");
  assert.ok(fs.existsSync(ANDROID_JAR), "android.jar is required to build the sample APK");
  const { java } = findJava();
  ensureKeystore();
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "auto-maker-"));
  const manifest = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.plainapp"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
    <application android:label="@string/app_name" android:appCategory="video" android:isGame="false" android:hasCode="false">
        <activity android:name="com.example.plainapp.MainActivity" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <meta-data android:name="distractionOptimized" android:value="true"/>
        </activity>
        <meta-data android:name="com.android.vending.derived.apk.id" android:value="99"/>
    </application>
</manifest>
`;
  const strings = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Plain App</string>
</resources>
`;
  fs.mkdirSync(path.join(directory, "res", "values"), { recursive: true });
  const manifestPath = path.join(directory, "AndroidManifest.xml");
  const stringsPath = path.join(directory, "res", "values", "strings.xml");
  fs.writeFileSync(manifestPath, manifest);
  fs.writeFileSync(stringsPath, strings);
  const compiled = path.join(directory, "compiled");
  fs.mkdirSync(compiled, { recursive: true });
  const unsigned = path.join(directory, "sample-unsigned.apk");
  const sample = path.join(directory, "sample.apk");
  await exec(AAPT2, ["compile", "-o", compiled, stringsPath]);
  await exec(AAPT2, [
    "link",
    "-o",
    unsigned,
    "-I",
    ANDROID_JAR,
    "--manifest",
    manifestPath,
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
    sample,
    unsigned,
  ]);

  const output = path.join(directory, "out", "signed.apk");
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const result = await runPipeline({ inputPath: sample, outputPath: output, log: [] });
  assert.ok(result.changes.some((change) => /game/i.test(change)));
  assert.equal(result.packageName, "com.example.plainapp");
  assert.ok(fs.statSync(output).size > 0);

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
    output,
  ]);
  const patched = fs.readFileSync(path.join(decoded, "AndroidManifest.xml"), "utf8");
  assert.match(patched, /android:appCategory="game"/);
  assert.match(patched, /android:isGame="true"/);
  assert.match(patched, /android.intent.category.CAR_LAUNCHER/);
  assert.match(patched, /android.hardware.type.automotive"/);
  assert.match(patched, /android:required="false"/);
  assert.doesNotMatch(patched, /distractionOptimized/);
  assert.doesNotMatch(patched, /derived\.apk\.id/);

  const navigationOutput = path.join(directory, "out", "navigation.apk");
  const navigation = await runPipeline({
    inputPath: sample,
    outputPath: navigationOutput,
    log: [],
    kind: "navigation",
  });
  assert.equal(navigation.packageName, "com.example.plainapp");
  const navigationDecoded = path.join(directory, "navigation-check");
  await exec(java, [
    "-jar",
    paths.apktool,
    "d",
    "-f",
    "-s",
    "-p",
    paths.framework,
    "-o",
    navigationDecoded,
    navigationOutput,
  ]);
  const navigationManifest = fs.readFileSync(path.join(navigationDecoded, "AndroidManifest.xml"), "utf8");
  const navigationXml = fs.readFileSync(path.join(navigationDecoded, "res", "xml", "automotive_app_desc.xml"), "utf8");
  assert.match(navigationManifest, /android.intent.category.CAR_LAUNCHER/);
  assert.match(navigationManifest, /androidx\.car\.app\.category\.NAVIGATION/);
  assert.match(navigationManifest, /com\.automaker\.nav\.NavigationCarService/);
  assert.match(navigationManifest, /androidx\.car\.app\.CarAppService/);
  assert.ok(fs.readFileSync(navigationOutput).includes(Buffer.from("Lcom/automaker/nav/NavigationCarService;")));
  assert.match(navigationManifest, /androidx\.car\.app\.NAVIGATION_TEMPLATES/);
  assert.match(navigationManifest, /androidx\.car\.app\.minCarApiLevel/);
  assert.match(navigationManifest, /com\.google\.android\.gms\.car\.application/);
  assert.doesNotMatch(navigationManifest, /android:name="android.app.Activity"/);
  assert.doesNotMatch(navigationManifest, /android:appCategory="game"/);
  assert.doesNotMatch(navigationManifest, /android:isGame="true"/);
  for (const name of ["media", "template", "service", "projection"]) {
    assert.match(navigationXml, new RegExp(`name="${name}"`));
  }
});
