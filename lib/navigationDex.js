import AdmZip from "adm-zip";
import fs from "node:fs";
import path from "node:path";
import { paths } from "./tools.js";

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

export function addNavigationDex(apkPath) {
  const zip = new AdmZip(apkPath);
  const original = zip.getEntries()
    .filter((entry) => /^classes\d*\.dex$/.test(entry.entryName))
    .map((entry) => entry.getData());
  const has = (marker) => original.some((bytes) => bytes.includes(Buffer.from(marker)));
  if (!has("Lcom/automaker/nav/NavigationCarService;")) {
    writeStoredDex(apkPath, fs.readFileSync(path.join(paths.tools, "navigation.dex")));
  }
  if (!has("Landroidx/lifecycle/LifecycleRegistry;")) {
    const lifecycle = path.join(paths.tools, "lifecycle.dex");
    if (fs.existsSync(lifecycle)) writeStoredDex(apkPath, fs.readFileSync(lifecycle));
  }
}
