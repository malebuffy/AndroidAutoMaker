import AdmZip from "adm-zip";
import { execFile, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { sidecarFiles } from "./sidecars.js";
import { extractZip, listBundle } from "./xapk.js";

export function adbCandidates() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const local = process.env.LOCALAPPDATA;
  const home = process.env.HOME;
  const exe = process.platform === "win32" ? "adb.exe" : "adb";
  return [
    sdk ? path.join(sdk, "platform-tools", exe) : "",
    local ? path.join(local, "Android", "Sdk", "platform-tools", "adb.exe") : "",
    home ? path.join(home, "Android", "Sdk", "platform-tools", exe) : "",
    `/opt/android-sdk/platform-tools/${exe}`,
    `/usr/lib/android-sdk/platform-tools/${exe}`,
    `/usr/bin/${exe}`,
    `/usr/local/bin/${exe}`,
  ].filter(Boolean);
}

function adbOnPath() {
  try {
    const result = runAdbVersion("adb");
    if (result) return "adb";
  } catch {
    // adb is not on PATH.
  }
  return "";
}

function runAdbVersion(bin) {
  const out = spawnSync(bin, ["version"], { encoding: "utf8", windowsHide: true });
  const text = `${out.stdout || ""}${out.stderr || ""}`;
  return /Android Debug Bridge/i.test(text) ? bin : "";
}

export function findAdb() {
  for (const candidate of adbCandidates()) {
    if (fs.existsSync(candidate)) return candidate;
  }
  if (adbOnPath()) return "adb";
  throw new Error("adb was not found. Install Android platform-tools and connect the phone with USB debugging.");
}

export function shellInstallArguments(remotePath) {
  return [
    "shell",
    "am",
    "start",
    "-a",
    "android.intent.action.INSTALL_PACKAGE",
    "-d",
    `file://${remotePath}`,
    "-t",
    "application/vnd.android.package-archive",
    "-f",
    "1",
    "--es",
    "android.intent.extra.INSTALLER_PACKAGE_NAME",
    "com.android.vending",
    "--es",
    "android.intent.extra.REFERRER_NAME",
    "android-app://com.android.vending",
    "--ei",
    "android.intent.extra.INSTALL_REASON",
    "1",
    "--ez",
    "android.intent.extra.NOT_UNKNOWN_SOURCE",
    "true",
  ];
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { windowsHide: true, timeout: 120000, maxBuffer: 8 * 1024 * 1024 }, (error, stdout, stderr) => {
      const output = `${stdout || ""}${stderr || ""}`.trim();
      if (error) {
        error.toolOutput = output;
        reject(error);
        return;
      }
      resolve(output);
    });
  });
}

export function connectedSerials(devicesOutput) {
  return devicesOutput
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line.endsWith("\tdevice"))
    .map((line) => line.split("\t")[0])
    .filter(Boolean);
}

export function sessionCreateArguments(sdkInt) {
  const args = ["shell", "pm", "install-create", "-r", "-i", "com.android.vending"];
  if (Number(sdkInt) >= 34) args.push("--originating-uri", "https://play.google.com/store/apps");
  return args;
}

export function parseSessionId(output) {
  const text = String(output);
  const match = text.match(/\[(-?\d+)\]/) || text.match(/session\D+(-?\d+)/i);
  return match ? Number(match[1]) : null;
}

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

export function packageUpdateTime(output) {
  const text = String(output || "");
  if (/Unable to find package/i.test(text)) return null;
  const match = text.match(/lastUpdateTime=([^\r\n]+)/);
  return match ? match[1].trim() : null;
}

export async function installApkOnDevice(apkPath, remoteName, packageName = "") {
  const adb = findAdb();
  const serials = connectedSerials(await run(adb, ["devices"]));
  if (serials.length === 0) {
    throw new Error("No phone is connected. Turn on USB debugging and accept this computer.");
  }
  const serial = serials[0];
  const remote = `/data/local/tmp/${remoteName}`;
  const before = await readPackageUpdateTime(adb, serial, packageName);
  await run(adb, ["-s", serial, "push", apkPath, remote]);
  const output = await run(adb, ["-s", serial, ...shellInstallArguments(remote)]);
  const installed = await waitForPackage(adb, serial, packageName, before);
  return { serial, output, openedInstaller: true, installed };
}

export async function installXapkOnDevice(xapkPath, remotePrefix, packageName = "") {
  const adb = findAdb();
  const serials = connectedSerials(await run(adb, ["devices"]));
  if (serials.length === 0) {
    throw new Error("No phone is connected. Turn on USB debugging and accept this computer.");
  }
  const serial = serials[0];
  const extracted = fs.mkdtempSync(path.join(os.tmpdir(), "automaker-xapk-"));
  const pushed = [];
  try {
    await extractZip(xapkPath, extracted);
    const bundle = listBundle(extracted, packageName);
    const before = await readPackageUpdateTime(adb, serial, packageName);
    if (bundle.apks.length === 1) {
      const remote = `/data/local/tmp/${remotePrefix}.apk`;
      await run(adb, ["-s", serial, "push", bundle.apks[0], remote]);
      const output = await run(adb, ["-s", serial, ...shellInstallArguments(remote)]);
      await pushObbs(adb, serial, bundle.obbs);
      const installed = await waitForPackage(adb, serial, packageName, before);
      return { serial, output, openedInstaller: true, installed };
    }
    const sdkText = await run(adb, ["-s", serial, "shell", "getprop ro.build.version.sdk"]);
    const sessionId = await createInstallSession(adb, serial, Number.parseInt(sdkText, 10));
    try {
      for (let index = 0; index < bundle.apks.length; index += 1) {
        const file = bundle.apks[index];
        const remote = `/data/local/tmp/${remotePrefix}-${index}.apk`;
        pushed.push(remote);
        await run(adb, ["-s", serial, "push", file, remote]);
        const split = index === 0 ? "base.apk" : `split${index}.apk`;
        await run(adb, [
          "-s",
          serial,
          "shell",
          `pm install-write -S ${fs.statSync(file).size} ${sessionId} ${split} ${remote}`,
        ]);
      }
      await run(adb, ["-s", serial, "shell", `pm install-commit ${sessionId}`]);
      await assignPlayInstaller(adb, serial, packageName);
      await pushObbs(adb, serial, bundle.obbs);
      const installed = await waitForPackage(adb, serial, packageName, before);
      return { serial, openedInstaller: false, installed };
    } catch (error) {
      await run(adb, ["-s", serial, "shell", `pm install-abandon ${sessionId}`]).catch(() => {});
      throw error;
    }
  } finally {
    for (const remote of pushed) {
      await run(adb, ["-s", serial, "shell", "rm", "-f", remote]).catch(() => {});
    }
    fs.rmSync(extracted, { recursive: true, force: true });
  }
}

async function readPackageUpdateTime(adb, serial, packageName) {
  if (!PACKAGE_NAME.test(packageName || "")) return null;
  try {
    const output = await run(adb, ["-s", serial, "shell", `dumpsys package ${packageName} | grep lastUpdateTime`]);
    return packageUpdateTime(output);
  } catch (error) {
    return packageUpdateTime(error.toolOutput || "");
  }
}

async function waitForPackage(adb, serial, packageName, before) {
  if (!PACKAGE_NAME.test(packageName || "")) return false;
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    const stamp = await readPackageUpdateTime(adb, serial, packageName);
    if (stamp && stamp !== before) return true;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return false;
}

async function assignPlayInstaller(adb, serial, packageName) {
  if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(packageName || "")) return;
  const commands = [
    `cmd package set-installer-package --user 0 ${packageName} com.android.vending`,
    `cmd package set-installer --user 0 ${packageName} com.android.vending`,
    `pm set-installer ${packageName} com.android.vending`,
  ];
  for (const command of commands) {
    try {
      await run(adb, ["-s", serial, "shell", command]);
      return;
    } catch {
    }
  }
}

async function createInstallSession(adb, serial, sdkInt) {
  const commands = [];
  if (Number(sdkInt) >= 34) {
    commands.push("pm install-create -r -i com.android.vending --originating-uri https://play.google.com/store/apps");
  }
  commands.push("pm install-create -r -i com.android.vending");
  commands.push("cmd package install-create -r -i com.android.vending");
  let last = "";
  for (const command of commands) {
    try {
      const output = await run(adb, ["-s", serial, "shell", command]);
      const sessionId = parseSessionId(output);
      if (sessionId != null) return sessionId;
      last = output;
    } catch (error) {
      last = error.toolOutput || error.message || last;
    }
  }
  const failure = new Error(last || "The phone installer could not be opened.");
  failure.toolOutput = last;
  throw failure;
}

export async function listDeviceApps() {
  const { adb, serial } = await connectedDevice();
  const [thirdParty, launcher] = await Promise.all([
    run(adb, ["-s", serial, "shell", "pm", "list", "packages", "-3"]),
    run(adb, [
      "-s",
      serial,
      "shell",
      "cmd",
      "package",
      "query-activities",
      "-a",
      "android.intent.action.MAIN",
      "-c",
      "android.intent.category.LAUNCHER",
    ]),
  ]);
  const names = new Set();
  for (const line of thirdParty.split(/\r?\n/)) {
    const name = line.trim().replace(/^package:/, "");
    if (PACKAGE_NAME.test(name)) names.add(name);
  }
  for (const match of launcher.matchAll(/packageName=([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/g)) {
    names.add(match[1]);
  }
  return [...names].sort().map((packageName) => ({ packageName, label: packageName }));
}

export async function pullInstalledApp(packageName, directory) {
  if (!PACKAGE_NAME.test(packageName)) throw new Error("Choose an installed app.");
  const { adb, serial } = await connectedDevice();
  const listed = await run(adb, ["-s", serial, "shell", "pm", "path", packageName]);
  const remotes = listed
    .split(/\r?\n/)
    .map((line) => line.trim().replace(/^package:/, ""))
    .filter((line) => line.endsWith(".apk"));
  if (remotes.length === 0) throw new Error("That app has no APK.");
  const bundle = path.join(directory, "bundle");
  const apkDir = path.join(bundle, "apks");
  fs.mkdirSync(apkDir, { recursive: true });
  const splits = [];
  for (let index = 0; index < remotes.length; index += 1) {
    const baseName = index === 0 ? `${packageName}.apk` : path.basename(remotes[index]).replace(/[^A-Za-z0-9._-]/g, "_");
    const local = path.join(apkDir, baseName.endsWith(".apk") ? baseName : `${baseName}.apk`);
    await run(adb, ["-s", serial, "pull", remotes[index], local]);
    splits.push({ file: `apks/${path.basename(local)}`, id: index === 0 ? "base" : path.basename(local, ".apk") });
  }
  await pullTree(adb, serial, `/sdcard/Android/obb/${packageName}`, path.join(bundle, "Android", "obb", packageName));
  await pullTree(adb, serial, `/sdcard/Android/data/${packageName}/files`, path.join(bundle, "Android", "data", packageName, "files"));
  fs.writeFileSync(
    path.join(bundle, "manifest.json"),
    `${JSON.stringify({ package_name: packageName, name: packageName, split_apks: splits }, null, 2)}\n`,
  );
  const packed = remotes.length > 1 || sidecarFiles(bundle).length > 0;
  if (packed) {
    const zip = new AdmZip();
    zip.addLocalFolder(bundle);
    zip.writeZip(path.join(directory, "input.xapk"));
  } else {
    fs.copyFileSync(path.join(apkDir, `${packageName}.apk`), path.join(directory, "input.apk"));
  }
  fs.rmSync(bundle, { recursive: true, force: true });
  return { fileName: packed ? `${packageName}.xapk` : `${packageName}.apk` };
}

export async function pushSidecars(sidecarRoot) {
  const files = sidecarFiles(sidecarRoot);
  if (files.length === 0) return;
  const { adb, serial } = await connectedDevice();
  for (const entry of files) {
    const remote = `/sdcard/${entry.relative}`;
    await run(adb, ["-s", serial, "shell", "mkdir", "-p", path.posix.dirname(remote)]);
    await run(adb, ["-s", serial, "push", entry.file, remote]);
  }
}

async function connectedDevice() {
  const adb = findAdb();
  const serials = connectedSerials(await run(adb, ["devices"]));
  if (serials.length === 0) {
    throw new Error("No phone is connected. Turn on USB debugging and accept this computer.");
  }
  return { adb, serial: serials[0] };
}

async function pullTree(adb, serial, remoteDir, localDir) {
  let listing = "";
  try {
    listing = await run(adb, ["-s", serial, "shell", "find", remoteDir, "-type", "f"]);
  } catch {
    return;
  }
  const files = listing
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith(`${remoteDir}/`));
  for (const remote of files) {
    const relative = remote.slice(remoteDir.length + 1);
    if (!relative || relative.includes("..")) continue;
    const local = path.join(localDir, relative);
    fs.mkdirSync(path.dirname(local), { recursive: true });
    await run(adb, ["-s", serial, "pull", remote, local]);
  }
}

async function pushObbs(adb, serial, obbs) {
  for (const obb of obbs) {
    const remoteDir = `/sdcard/Android/obb/${obb.packageName}`;
    const remote = `${remoteDir}/${obb.name}`;
    try {
      await run(adb, ["-s", serial, "shell", "mkdir", "-p", remoteDir]);
      await run(adb, ["-s", serial, "push", obb.file, remote]);
    } catch {
    }
  }
}
