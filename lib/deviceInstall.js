import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { extractZip, listBundle } from "./xapk.js";

export function adbCandidates() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const local = process.env.LOCALAPPDATA;
  return [
    sdk ? path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb") : "",
    local ? path.join(local, "Android", "Sdk", "platform-tools", "adb.exe") : "",
  ].filter(Boolean);
}

export function findAdb() {
  for (const candidate of adbCandidates()) {
    if (fs.existsSync(candidate)) return candidate;
  }
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
    extractZip(xapkPath, extracted);
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
