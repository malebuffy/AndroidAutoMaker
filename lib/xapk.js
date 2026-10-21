import AdmZip from "adm-zip";
import { execFile } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { runPipeline } from "./pipeline.js";
import { assertTools, paths } from "./tools.js";

export function assertSafeEntry(root, entryName) {
  const name = String(entryName).replace(/\\/g, "/");
  if (!name || name.startsWith("/") || /^[A-Za-z]:/.test(name) || name.includes("\0")) {
    throw new Error("The XAPK contains an unsafe path.");
  }
  const target = path.resolve(root, name);
  const relative = path.relative(root, target);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error("The XAPK contains an unsafe path.");
  }
}

export function extractZip(zipPath, dest) {
  const root = path.resolve(dest);
  fs.mkdirSync(root, { recursive: true });
  const zip = new AdmZip(zipPath);
  for (const entry of zip.getEntries()) {
    assertSafeEntry(root, entry.entryName);
  }
  zip.extractAllTo(root, true);
}

export function packZip(sourceDir, destPath) {
  const zip = new AdmZip();
  addTree(zip, sourceDir, "");
  if (fs.existsSync(destPath)) fs.rmSync(destPath, { force: true });
  zip.writeZip(destPath);
  if (!fs.existsSync(destPath)) throw new Error("The XAPK could not be packed.");
}

function addTree(zip, dir, prefix) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      addTree(zip, full, prefix ? `${prefix}/${entry.name}` : entry.name);
    } else {
      zip.addLocalFile(full, prefix);
    }
  }
}

function listApks(root) {
  const found = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.toLowerCase().endsWith(".apk")) found.push(full);
    }
  }
  walk(root);
  return found;
}

function relativeZipPath(root, file) {
  return path.relative(root, file).split(path.sep).join("/");
}

export function isAuxiliarySplit(fileName) {
  const lower = path.basename(fileName).toLowerCase();
  return /^(config\.|split_)/.test(lower) || /asset[-_]?pack/.test(lower);
}

function readManifest(root) {
  const manifestPath = path.join(root, "manifest.json");
  if (!fs.existsSync(manifestPath)) return null;
  const text = fs.readFileSync(manifestPath, "utf8").replace(/^\uFEFF/, "");
  try {
    return JSON.parse(text);
  } catch {
    throw new Error("The XAPK manifest.json could not be read.");
  }
}

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

export function listBundle(root, packageName = "") {
  const apks = listApks(root);
  const base = chooseBaseApk(root, apks);
  const ordered = [base, ...apks.filter((file) => path.resolve(file) !== path.resolve(base))];
  const manifest = readManifest(root);
  const fallback = String(packageName || manifest?.package_name || "").trim();
  const obbs = listFiles(root)
    .filter((file) => file.toLowerCase().endsWith(".obb"))
    .map((file) => ({
      file,
      name: path.basename(file),
      packageName: fallback || packageFromObbPath(root, file),
    }))
    .filter((item) => PACKAGE_NAME.test(item.packageName) && /^[A-Za-z0-9._-]+$/.test(item.name));
  return { apks: ordered, obbs };
}

function listFiles(root) {
  const found = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else found.push(full);
    }
  }
  walk(root);
  return found;
}

function packageFromObbPath(root, file) {
  const relative = relativeZipPath(root, file);
  const match = relative.match(/(?:^|\/)obb\/([^/]+)\//i);
  return match ? match[1] : "";
}

export function chooseBaseApk(root, apks = listApks(root)) {
  if (!apks.length) throw new Error("The XAPK does not contain an APK.");
  const manifest = readManifest(root);
  const byZipPath = new Map(apks.map((file) => [relativeZipPath(root, file), file]));

  if (manifest?.split_apks?.length) {
    const listedBase = manifest.split_apks.find((item) => item && (item.id === "base" || item.id === "base.apk"));
    const listedPath = listedBase?.file?.replace(/\\/g, "/").replace(/^\.\//, "");
    if (listedPath && byZipPath.has(listedPath)) return byZipPath.get(listedPath);

    for (const item of manifest.split_apks) {
      const file = item?.file?.replace(/\\/g, "/").replace(/^\.\//, "");
      if (!file || /(^|\/)(config\.|split_)/.test(file)) continue;
      if (byZipPath.has(file)) return byZipPath.get(file);
    }
  }

  const installable = apks.filter((file) => !isAuxiliarySplit(file));
  const pool = installable.length ? installable : apks;

  if (manifest?.package_name) {
    const named = pool.find((file) => path.basename(file) === `${manifest.package_name}.apk`);
    if (named) return named;
  }

  if (pool.length === 1) return pool[0];

  return pool
    .map((file) => ({ file, score: apkScore(file) }))
    .sort((left, right) => right.score - left.score || fs.statSync(right.file).size - fs.statSync(left.file).size)[0].file;
}

function apkScore(file) {
  const signals = manifestSignals(file);
  const stem = path.basename(file, ".apk");
  let score = 0;
  if (signals.launcher) score += 10;
  if (!signals.featureSplit) score += 5;
  if (stem.includes(".")) score += 3;
  if (stem.toLowerCase() === "base") score += 2;
  return score;
}

function manifestSignals(apkPath) {
  const data = readZipEntry(apkPath, "AndroidManifest.xml");
  if (!data) return { launcher: false, featureSplit: false };
  const blob = `${data.toString("utf16le")}\n${data.toString("latin1")}`;
  return {
    launcher: blob.includes("android.intent.category.LAUNCHER"),
    featureSplit: blob.includes("isFeatureSplit") || blob.includes("asset-pack"),
  };
}

function readZipEntry(zipPath, wantedName) {
  const fd = fs.openSync(zipPath, "r");
  try {
    const size = fs.fstatSync(fd).size;
    const tailLen = Math.min(size, 22 + 65535);
    const tail = Buffer.alloc(tailLen);
    fs.readSync(fd, tail, 0, tailLen, size - tailLen);
    let eocd = -1;
    for (let index = tail.length - 22; index >= 0; index -= 1) {
      if (tail.readUInt32LE(index) === 0x06054b50) {
        eocd = index;
        break;
      }
    }
    if (eocd < 0) return null;
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOffset = tail.readUInt32LE(eocd + 16);
    if (cdSize === 0xffffffff || cdOffset === 0xffffffff) return null;
    const directory = Buffer.alloc(cdSize);
    fs.readSync(fd, directory, 0, cdSize, cdOffset);
    let offset = 0;
    while (offset + 46 <= directory.length) {
      if (directory.readUInt32LE(offset) !== 0x02014b50) break;
      const method = directory.readUInt16LE(offset + 10);
      const compressedSize = directory.readUInt32LE(offset + 20);
      const nameLength = directory.readUInt16LE(offset + 28);
      const extraLength = directory.readUInt16LE(offset + 30);
      const commentLength = directory.readUInt16LE(offset + 32);
      const localOffset = directory.readUInt32LE(offset + 42);
      const name = directory.toString("utf8", offset + 46, offset + 46 + nameLength);
      if (name === wantedName) {
        const local = Buffer.alloc(30);
        fs.readSync(fd, local, 0, 30, localOffset);
        const dataOffset = localOffset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28);
        const compressed = Buffer.alloc(compressedSize);
        fs.readSync(fd, compressed, 0, compressedSize, dataOffset);
        if (method === 0) return compressed;
        if (method === 8) return zlib.inflateRawSync(compressed);
        return null;
      }
      offset += 46 + nameLength + extraLength + commentLength;
    }
    return null;
  } catch {
    return null;
  } finally {
    fs.closeSync(fd);
  }
}

function refreshManifestSize(root) {
  const manifestPath = path.join(root, "manifest.json");
  if (!fs.existsSync(manifestPath)) return;
  const manifest = readManifest(root);
  if (!manifest || typeof manifest !== "object") return;
  let total = 0;
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (path.resolve(full) !== path.resolve(manifestPath)) total += fs.statSync(full).size;
    }
  }
  walk(root);
  manifest.total_size = total;
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

const MERGE_JAR = path.join(paths.tools, "lib", "ARSCLib-1.3.8.jar");
const MERGE_CLASSES = path.join(paths.tools, "merge");

async function mergeXapkToApk(java, inputPath, outputPath) {
  await run(java, [
    "-cp",
    `${MERGE_CLASSES}${path.delimiter}${MERGE_JAR}`,
    "com.automaker.merge.MergeXapk",
    inputPath,
    outputPath,
  ]);
  if (!fs.existsSync(outputPath)) throw new Error("The XAPK could not be merged.");
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { windowsHide: true, maxBuffer: 32 * 1024 * 1024 }, (error, stdout, stderr) => {
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

export async function runXapkPipeline({ inputPath, outputPath, onStep = () => {}, log = [], kind = "game" }) {
  const { java } = assertTools();
  const directory = path.dirname(outputPath);
  const merged = path.join(directory, "merged.apk");
  onStep("decode");
  log.push("Extracting the XAPK.");
  fs.mkdirSync(directory, { recursive: true });
  await mergeXapkToApk(java, inputPath, merged);
  const patched = await runPipeline({
    inputPath: merged,
    outputPath,
    onStep,
    log,
    kind,
  });
  fs.rmSync(merged, { force: true });
  return {
    changes: patched.changes,
    packageName: patched.packageName,
    outputPath,
    size: fs.statSync(outputPath).size,
    format: "apk",
  };
}
