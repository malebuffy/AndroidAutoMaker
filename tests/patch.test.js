import test from "node:test";
import assert from "node:assert/strict";
import { patchManifest } from "../lib/patch.js";

const SAMPLE = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.plain" android:versionCode="1" android:versionName="1.0">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
    <uses-feature android:name="android.hardware.type.automotive" android:required="true"/>
    <application android:label="Plain" android:appCategory="video" android:isGame="false">
        <activity android:name=".Main" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <meta-data
                android:name="distractionOptimized"
                android:value="true"/>
        </activity>
        <activity android:name=".Other" android:exported="false"/>
        <activity-alias android:name=".Alias" android:targetActivity=".Main" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
                <category android:name="android.intent.category.CAR_LAUNCHER"/>
            </intent-filter>
        </activity-alias>
        <meta-data android:name="com.android.vending.derived.apk.id" android:value="123"/>
    </application>
</manifest>
`;

test("declares a parked game and keeps the edit stable", () => {
  const first = patchManifest(SAMPLE);
  assert.match(first.xml, /<application\b[^>]*android:appCategory="game"/);
  assert.match(first.xml, /<application\b[^>]*android:isGame="true"/);
  assert.doesNotMatch(first.xml, /appCategory="video"/);
  assert.doesNotMatch(first.xml, /isGame="false"/);
  assert.match(first.xml, /android.hardware.gamepad"[^>]*android:required="false"/);
  assert.doesNotMatch(first.xml, /android.hardware.type.automotive"[^>]*android:required="true"/);
  assert.equal((first.xml.match(/android.intent.category.CAR_LAUNCHER/g) || []).length, 2);
  assert.match(first.xml, /<activity\b[^>]*android:name="\.Main"[^>]*android:exported="true"/);
  assert.match(first.xml, /<activity\b[^>]*android:name="\.Other"[^>]*android:exported="false"/);
  assert.doesNotMatch(first.xml, /distractionOptimized/);
  assert.doesNotMatch(first.xml, /derived\.apk\.id/);
  assert.equal(first.packageName, "com.example.plain");

  const second = patchManifest(first.xml);
  assert.equal(second.xml, first.xml);
});

test("adds a car launcher filter when the app has no launcher", () => {
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.bare">
    <application android:label="Bare">
        <activity android:name=".Only" android:exported="false"/>
    </application>
</manifest>`;
  const result = patchManifest(xml);
  assert.match(result.xml, /android.intent.category.CAR_LAUNCHER/);
  assert.match(result.xml, /android:exported="true"/);
  assert.match(result.xml, /android:appCategory="game"/);
  const again = patchManifest(result.xml);
  assert.equal(again.xml, result.xml);
});

test("declares navigation the way FS Play does, without marking it as a game", () => {
  const result = patchManifest(SAMPLE, "navigation");
  assert.doesNotMatch(result.xml, /android:appCategory="game"/);
  assert.doesNotMatch(result.xml, /android:isGame="true"/);
  assert.match(result.xml, /android.intent.category.CAR_LAUNCHER/);
  assert.match(result.xml, /android.intent.category.APP_MAPS/);
  assert.match(result.xml, /androidx\.car\.app\.category\.NAVIGATION/);
  assert.match(result.xml, /android:name="distractionOptimized"[^>]*android:value="true"/);
  assert.match(result.xml, /androidx\.car\.app\.NAVIGATION_TEMPLATES/);
  assert.match(result.xml, /androidx\.car\.app\.ACCESS_SURFACE/);
  assert.match(result.xml, /androidx\.car\.app\.minCarApiLevel"[^>]*android:value="5"/);
  assert.match(result.xml, /androidx\.car\.app\.CarAppService/);
  assert.match(result.xml, /com\.automaker\.nav\.NavigationCarService/);
  assert.match(result.xml, /com\.google\.android\.gms\.car\.application/);
  assert.match(result.xml, /android.hardware.gamepad"[^>]*android:required="false"/);
  assert.doesNotMatch(result.xml, /android:name="android\.app\.Activity"/);
  assert.doesNotMatch(result.xml, /derived\.apk\.id/);
  const second = patchManifest(result.xml, "navigation");
  assert.equal(second.xml, result.xml);
});
