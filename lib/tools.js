import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const paths = {
  root,
  tools: path.join(root, "tools"),
  work: path.join(root, "work"),
  framework: path.join(root, "tools", "framework"),
  keys: path.join(root, "tools", "keys"),
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

export const SIGNING_ALIAS = "automaker";
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

// Each converted package gets its own random key, created the first time that package is
// converted and reused afterwards so a reconverted app installs as an update. The password is
// random too and is passed to the tools by file, never on the command line.
export function ensureSigningKey(packageName) {
  if (!PACKAGE_NAME.test(packageName || "")) {
    throw new Error("The package name could not be read, so no signing key was chosen.");
  }
  const keystore = path.join(paths.keys, `${packageName}.p12`);
  const passwordFile = path.join(paths.keys, `${packageName}.pass`);
  if (fs.existsSync(keystore) && fs.existsSync(passwordFile)) return { keystore, passwordFile };

  const { keytool } = findJava();
  fs.mkdirSync(paths.keys, { recursive: true, mode: 0o700 });
  fs.rmSync(keystore, { force: true });
  fs.writeFileSync(passwordFile, crypto.randomBytes(32).toString("base64url"), { mode: 0o600 });
  const result = spawnSync(
    keytool,
    [
      "-genkeypair",
      "-storetype",
      "PKCS12",
      "-keystore",
      keystore,
      "-alias",
      SIGNING_ALIAS,
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "10000",
      "-storepass:file",
      passwordFile,
      "-dname",
      `CN=${packageName}, O=Android Auto Maker`,
    ],
    { encoding: "utf8", windowsHide: true },
  );
  if (result.status !== 0 || !fs.existsSync(keystore)) {
    fs.rmSync(keystore, { force: true });
    fs.rmSync(passwordFile, { force: true });
    throw new Error(`Could not create the signing key.\n${result.stderr || result.stdout || ""}`.trim());
  }
  fs.chmodSync(keystore, 0o600);
  return { keystore, passwordFile };
}

export function assertTools() {
  const missing = ["apktool", "apksigner", "zipalign"].filter((name) => !fs.existsSync(paths[name]));
  if (missing.length) {
    throw new Error(`Missing tools: ${missing.join(", ")}. Expected them in the tools folder.`);
  }
  const java = findJava();
  fs.mkdirSync(paths.framework, { recursive: true });
  fs.mkdirSync(paths.work, { recursive: true });
  return java;
}
