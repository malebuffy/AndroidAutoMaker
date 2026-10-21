import { execFile } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { navigationDescriptorName, patchManifest } from "./patch.js";
import { addNavigationDex } from "./navigationDex.js";
import { assertTools, paths } from "./tools.js";

export const STEPS = [
  { id: "decode", label: "Prepare" },
  { id: "patch", label: "Update" },
  { id: "build", label: "Build" },
  { id: "sign", label: "Finish" },
];

function run(command, args) {
  return new Promise((resolve, reject) => {
    execFile(
      command,
      args,
      { maxBuffer: 64 * 1024 * 1024, timeout: 15 * 60 * 1000, windowsHide: true },
      (error, stdout, stderr) => {
        const output = `${stdout || ""}${stderr || ""}`.trim();
        if (error) {
          error.toolOutput = output;
          reject(error);
          return;
        }
        resolve(output);
      },
    );
  });
}

async function logged(log, command, args) {
  const printable = args.map((arg) => (/\s/.test(arg) ? `"${arg}"` : arg)).join(" ");
  log.push(`$ ${path.basename(command)} ${printable}`);
  const output = await run(command, args);
  if (output) log.push(output);
  return output;
}

function rm(target) {
  fs.rmSync(target, { recursive: true, force: true });
}

async function decode(java, input, output, log, mode) {
  rm(output);
  const args = ["-Xmx2048m", "-Duser.language=en", "-jar", paths.apktool, "d", "-f", "-s"];
  if (mode === "broken") args.push("--keep-broken-res");
  args.push("-p", paths.framework, "-o", output, input);
  await logged(log, java, args);
}

async function build(java, decoded, output, log) {
  if (fs.existsSync(output)) fs.rmSync(output, { force: true });
  await logged(log, java, [
    "-Xmx2048m",
    "-Duser.language=en",
    "-jar",
    paths.apktool,
    "b",
    "-f",
    "-p",
    paths.framework,
    "-o",
    output,
    decoded,
  ]);
  if (!fs.existsSync(output)) {
    throw new Error("apktool finished without writing an APK.");
  }
}

const NAVIGATION_USES = ["media", "template", "service", "projection"];

const NAVIGATION_XML = `<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
${NAVIGATION_USES.map((name) => `    <uses name="${name}"/>`).join("\n")}
</automotiveApp>
`;

function withNavigationUses(current) {
  if (!/<\/automotiveApp>/.test(current)) return `${NAVIGATION_XML}\n`;
  let next = current;
  for (const name of NAVIGATION_USES) {
    if (new RegExp(`<uses\\b[^>]*\\bname="${name}"`).test(next)) continue;
    next = next.replace(/<\/automotiveApp>/, `    <uses name="${name}"/>\n</automotiveApp>`);
  }
  return next.endsWith("\n") ? next : `${next}\n`;
}

function writeNavigationDescriptor(decoded, manifestXml) {
  const name = navigationDescriptorName(manifestXml);
  const file = path.join(decoded, "res", "xml", `${name}.xml`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const current = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
  fs.writeFileSync(file, current ? withNavigationUses(current) : `${NAVIGATION_XML}\n`, "utf8");
}

function applyManifestPatch(decoded, kind) {
  const manifestPath = path.join(decoded, "AndroidManifest.xml");
  if (!fs.existsSync(manifestPath)) {
    throw new Error("The decoded APK has no AndroidManifest.xml.");
  }
  const result = patchManifest(fs.readFileSync(manifestPath, "utf8"), kind);
  fs.writeFileSync(manifestPath, result.xml, "utf8");
  if (kind === "navigation") writeNavigationDescriptor(decoded, result.xml);
  return result;
}

export async function alignAndSign(java, inputPath, outputPath, log) {
  const aligned = path.join(path.dirname(outputPath), `.aligning-${path.basename(outputPath)}`);
  try {
    await logged(log, paths.zipalign, ["-P", "16", "-f", "4", inputPath, aligned]);
  } catch (error) {
    log.push("16 KB alignment failed. Aligning to 4 bytes.");
    if (error.toolOutput) log.push(error.toolOutput);
    await logged(log, paths.zipalign, ["-p", "-f", "4", inputPath, aligned]);
  }
  await logged(log, java, [
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
    outputPath,
    aligned,
  ]);
  await logged(log, java, ["-jar", paths.apksigner, "verify", "--verbose", outputPath]);
  fs.rmSync(aligned, { force: true });
}

export async function runPipeline({ inputPath, outputPath, onStep = () => {}, log = [], kind = "game" }) {
  const { java } = assertTools();
  const directory = path.dirname(outputPath);
  const decoded = path.join(directory, "decoded");
  const unsigned = path.join(directory, "unsigned.apk");

  onStep("decode");
  try {
    await decode(java, inputPath, decoded, log, "full");
  } catch (error) {
    log.push("Decode failed. Trying again and keeping broken resources.");
    if (error.toolOutput) log.push(error.toolOutput);
    await decode(java, inputPath, decoded, log, "broken");
  }

  onStep("patch");
  const patched = applyManifestPatch(decoded, kind === "navigation" ? "navigation" : "game");
  for (const change of patched.changes) log.push(change);

  onStep("build");
  await build(java, decoded, unsigned, log);
  if (kind === "navigation") addNavigationDex(unsigned);

  onStep("sign");
  await alignAndSign(java, unsigned, outputPath, log);

  rm(decoded);
  fs.rmSync(unsigned, { force: true });

  return {
    changes: patched.changes,
    packageName: patched.packageName,
    outputPath,
    size: fs.statSync(outputPath).size,
    format: "apk",
  };
}
