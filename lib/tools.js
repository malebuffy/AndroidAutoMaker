import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const paths = {
  root,
  tools: path.join(root, "tools"),
  work: path.join(root, "work"),
  framework: path.join(root, "tools", "framework"),
  keystore: path.join(root, "tools", "debug.keystore"),
  apktool: path.join(root, "tools", "apktool.jar"),
  apksigner: path.join(root, "tools", "apksigner.jar"),
  zipalign: path.join(root, "tools", "zipalign.exe"),
};

const JAVA_CANDIDATES = [
  process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, "bin", "java.exe"),
  "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
  "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe",
  "C:\\Program Files\\Eclipse Adoptium\\jdk-21\\bin\\java.exe",
  "C:\\Program Files\\Eclipse Adoptium\\jdk-17\\bin\\java.exe",
].filter(Boolean);

function javaMajor(bin) {
  const result = spawnSync(bin, ["-version"], { encoding: "utf8", windowsHide: true });
  const text = `${result.stdout || ""}${result.stderr || ""}`;
  const match = text.match(/version "(?:1\.)?(\d+)/);
  return match ? Number(match[1]) : 0;
}

export function findJava() {
  let best = null;
  let bestMajor = 0;
  for (const candidate of JAVA_CANDIDATES) {
    if (!fs.existsSync(candidate)) continue;
    const major = javaMajor(candidate);
    if (major >= 11 && major > bestMajor) {
      best = candidate;
      bestMajor = major;
    }
  }
  if (!best) {
    throw new Error("Java 11 or newer is required. Install a JDK and set JAVA_HOME.");
  }
  return { java: best, keytool: path.join(path.dirname(best), "keytool.exe"), major: bestMajor };
}

export function ensureKeystore() {
  const { keytool } = findJava();
  fs.mkdirSync(paths.tools, { recursive: true });
  if (fs.existsSync(paths.keystore)) return paths.keystore;
  const result = spawnSync(
    keytool,
    [
      "-genkeypair",
      "-keystore",
      paths.keystore,
      "-alias",
      "androiddebugkey",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "10000",
      "-storepass",
      "android",
      "-keypass",
      "android",
      "-dname",
      "CN=Android Auto Maker, OU=Local, O=Local, C=US",
    ],
    { encoding: "utf8", windowsHide: true },
  );
  if (result.status !== 0) {
    throw new Error(`Could not create the signing key.\n${result.stderr || result.stdout || ""}`.trim());
  }
  return paths.keystore;
}

export function assertTools() {
  const missing = ["apktool", "apksigner", "zipalign"].filter((name) => !fs.existsSync(paths[name]));
  if (missing.length) {
    throw new Error(`Missing tools: ${missing.join(", ")}. Expected them in the tools folder.`);
  }
  const java = findJava();
  ensureKeystore();
  fs.mkdirSync(paths.framework, { recursive: true });
  fs.mkdirSync(paths.work, { recursive: true });
  return java;
}
