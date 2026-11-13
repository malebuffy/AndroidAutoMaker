import AdmZip from "adm-zip";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { paths } from "./tools.js";

const CAR_RESOURCES = [
  ["string", "car_app_library_version", 0x51100001],
  ["integer", "content_limit_grid", 0x51100002],
  ["integer", "content_limit_pane", 0x51100003],
  ["integer", "content_limit_place_list", 0x51100004],
  ["integer", "content_limit_route_list", 0x51100005],
  ["integer", "content_limit_list", 0x51100006],
];

function writeStoredDex(apkPath, dex) {
  const zip = new AdmZip(apkPath);
  const names = new Set(zip.getEntries().map((entry) => entry.entryName));
  let index = 2;
  while (names.has(`classes${index}.dex`)) index += 1;
  const name = `classes${index}.dex`;
  zip.addFile(name, dex);
  const added = zip.getEntry(name);
  added.header.method = 0;
  added.header.compressedSize = dex.length;
  added.header.size = dex.length;
  zip.writeZip(apkPath);
}

function findAapt() {
  const exe = process.platform === "win32" ? "aapt.exe" : "aapt";
  const roots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, "Android", "Sdk"),
    process.env.HOME && path.join(process.env.HOME, "Android", "Sdk"),
    "/opt/android-sdk",
    "/usr/lib/android-sdk",
  ].filter(Boolean);
  for (const sdk of roots) {
    const buildTools = path.join(sdk, "build-tools");
    if (!fs.existsSync(buildTools)) continue;
    const versions = fs.readdirSync(buildTools).sort().reverse();
    for (const version of versions) {
      const candidate = path.join(buildTools, version, exe);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  for (const candidate of [`/usr/bin/${exe}`, `/usr/local/bin/${exe}`]) {
    if (fs.existsSync(candidate)) return candidate;
  }
  // Last resort: aapt on PATH ( distro package `aapt` / `google-android-build-tools` ).
  const probed = spawnSync(exe, ["version"], { encoding: "utf8", windowsHide: true });
  if (probed.status === 0) return exe;
  return null;
}

function carResourceIds(apkPath) {
  const aapt = findAapt();
  if (!aapt) throw new Error("Android SDK build-tools aapt is required to add the car library resources.");
  const result = spawnSync(aapt, ["dump", "resources", apkPath], {
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 128 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(`Could not read the package resources.\n${result.stderr || result.stdout || ""}`.trim());
  }
  const ids = new Map();
  const pattern = /resource (0x[0-9a-fA-F]+) \S+:(string|integer)\/([A-Za-z0-9_]+)/g;
  for (const match of String(result.stdout).matchAll(pattern)) {
    const key = `${match[2]}/${match[3]}`;
    if (!ids.has(key)) ids.set(key, Number.parseInt(match[1], 16));
  }
  return CAR_RESOURCES.map(([type, name, sentinel]) => {
    const id = ids.get(`${type}/${name}`);
    if (!id) throw new Error(`The car library resource ${type}/${name} was not added to the package.`);
    return [sentinel, id];
  });
}

function patchSupportDex(dex, replacements) {
  const out = Buffer.from(dex);
  for (const [sentinel, id] of replacements) {
    const from = Buffer.alloc(4);
    const to = Buffer.alloc(4);
    from.writeUInt32LE(sentinel >>> 0);
    to.writeUInt32LE(id >>> 0);
    let index = out.indexOf(from);
    if (index < 0) throw new Error("The car support library is missing a resource placeholder.");
    while (index >= 0) {
      to.copy(out, index);
      index = out.indexOf(from, index + 4);
    }
  }
  crypto.createHash("sha1").update(out.subarray(32)).digest().copy(out, 12);
  const sum = adler32(out, 12);
  out.writeUInt32LE(sum >>> 0, 8);
  return out;
}

function adler32(buf, start) {
  let a = 1;
  let b = 0;
  for (let i = start; i < buf.length; i += 1) {
    a = (a + buf[i]) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

export function writeCarLibraryValues(decoded) {
  const valuesDir = path.join(decoded, "res", "values");
  const integers = {
    content_limit_grid: 6,
    content_limit_pane: 4,
    content_limit_place_list: 6,
    content_limit_route_list: 3,
    content_limit_list: 6,
  };
  const present = new Set();
  if (fs.existsSync(valuesDir)) {
    for (const file of fs.readdirSync(valuesDir)) {
      if (!file.endsWith(".xml")) continue;
      const xml = fs.readFileSync(path.join(valuesDir, file), "utf8");
      if (/<string\b[^>]*\bname="car_app_library_version"/.test(xml)) present.add("car_app_library_version");
      for (const name of Object.keys(integers)) {
        if (new RegExp(`<integer\\b[^>]*\\bname="${name}"`).test(xml)) present.add(name);
      }
    }
  }
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"];
  if (!present.has("car_app_library_version")) {
    lines.push('    <string name="car_app_library_version" translatable="false">1.7.0</string>');
  }
  for (const [name, value] of Object.entries(integers)) {
    if (!present.has(name)) lines.push(`    <integer name="${name}">${value}</integer>`);
  }
  if (lines.length === 2) return;
  lines.push("</resources>", "");
  fs.mkdirSync(valuesDir, { recursive: true });
  fs.writeFileSync(path.join(valuesDir, "automaker_car.xml"), lines.join("\n"), "utf8");
}

export function addKeyboardDex(apkPath) {
  const zip = new AdmZip(apkPath);
  const present = zip.getEntries()
    .filter((entry) => /^classes\d*\.dex$/.test(entry.entryName))
    .some((entry) => entry.getData().includes(Buffer.from("Lcom/automaker/game/KeyboardStay;")));
  if (!present) writeStoredDex(apkPath, fs.readFileSync(path.join(paths.tools, "keyboard.dex")));
}

export function addNavigationDex(apkPath) {
  const zip = new AdmZip(apkPath);
  const original = zip.getEntries()
    .filter((entry) => /^classes\d*\.dex$/.test(entry.entryName))
    .map((entry) => entry.getData());
  const has = (marker) => original.some((bytes) => bytes.includes(Buffer.from(marker)));
  if (!has("Lcom/automaker/nav/NavigationCarService;")) {
    writeStoredDex(apkPath, fs.readFileSync(path.join(paths.tools, "navigation.dex")));
  }
  if (!has("Landroidx/car/app/serialization/Bundler;")) {
    writeStoredDex(apkPath, fs.readFileSync(path.join(paths.tools, "car.dex")));
  }
  if (!has("Landroidx/lifecycle/LifecycleRegistry;")) {
    const lifecycle = path.join(paths.tools, "lifecycle.dex");
    if (fs.existsSync(lifecycle)) writeStoredDex(apkPath, fs.readFileSync(lifecycle));
  }
  if (!has("Landroidx/arch/core/internal/FastSafeIterableMap;")) {
    writeStoredDex(apkPath, fs.readFileSync(path.join(paths.tools, "arch.dex")));
  }
  if (!has("Landroidx/activity/OnBackPressedDispatcher;")) {
    const activity = path.join(paths.tools, "activity.dex");
    if (fs.existsSync(activity)) writeStoredDex(apkPath, fs.readFileSync(activity));
  }
  if (!has("Lrikka/shizuku/ShizukuProvider;")) {
    const shizuku = path.join(paths.tools, "shizuku.dex");
    if (fs.existsSync(shizuku)) writeStoredDex(apkPath, fs.readFileSync(shizuku));
  }
  const support = patchSupportDex(
    fs.readFileSync(path.join(paths.tools, "car-support.dex")),
    carResourceIds(apkPath),
  );
  writeStoredDex(apkPath, support);
  writeStoredFile(apkPath, "car-app-api.level", Buffer.from("8\n", "utf8"));
}

function writeStoredFile(apkPath, name, bytes) {
  const zip = new AdmZip(apkPath);
  if (zip.getEntry(name)) return;
  zip.addFile(name, bytes);
  const added = zip.getEntry(name);
  added.header.method = 0;
  added.header.compressedSize = bytes.length;
  added.header.size = bytes.length;
  zip.writeZip(apkPath);
}
