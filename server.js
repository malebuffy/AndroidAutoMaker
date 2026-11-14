import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import express from "express";
import multer from "multer";
import { runPipeline, STEPS } from "./lib/pipeline.js";
import { assertTools, findJava, paths } from "./lib/tools.js";
import { installApkOnDevice, listDeviceApps, pullInstalledApp, pushSidecars } from "./lib/deviceInstall.js";
import { PATCHED_SUFFIX } from "./lib/packageRename.js";
import { createDonationCheckout, loadLocalEnv, readDonationCheckout } from "./lib/donation.js";
// import { installXapkOnDevice } from "./lib/deviceInstall.js";
import { runXapkPipeline } from "./lib/xapk.js";

loadLocalEnv(paths.root);

const PORT = Number(process.env.PORT) || 4173;
const HOST = process.env.HOST || "0.0.0.0";
const JOB_TTL_MS = 60 * 60 * 1000;
const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const jobs = new Map();
const pendingDonations = new Map();
const pendingDonationFile = path.join(paths.work, "donations.json");

function savePendingDonations() {
  fs.mkdirSync(paths.work, { recursive: true });
  fs.writeFileSync(pendingDonationFile, JSON.stringify([...pendingDonations.values()]));
}

function loadPendingDonations() {
  if (!fs.existsSync(pendingDonationFile)) return;
  try {
    const records = JSON.parse(fs.readFileSync(pendingDonationFile, "utf8"));
    for (const record of records) {
      if (record && record.sessionId) pendingDonations.set(record.sessionId, record);
    }
  } catch (error) {
    pendingDonations.clear();
  }
}

loadPendingDonations();

function publicJob(job) {
  return {
    id: job.id,
    status: job.status,
    step: job.step,
    steps: STEPS.map((step) => ({
      id: step.id,
      label: step.label,
      state: stepState(job, step.id),
    })),
    kind: job.kind === "navigation" ? "navigation" : "game",
    log: job.log.slice(-200),
    progress: job.progress,
    progressLabel: job.progressLabel || "",
    error: job.error,
    changes: job.changes,
    packageName: job.packageName,
    downloadName: job.downloadName,
    size: job.size,
    originalName: job.originalName,
    format: job.format,
  };
}

function stepState(job, id) {
  const index = STEPS.findIndex((step) => step.id === id);
  const current = STEPS.findIndex((step) => step.id === job.step);
  if (job.status === "error" && index === current) return "error";
  if (job.status === "done" || index < current) return "done";
  if (index === current) return "active";
  return "pending";
}

function safeDownloadName(originalName) {
  const base = path.basename(originalName || "app.apk").replace(/[^a-zA-Z0-9._-]+/g, "_");
  const lower = base.toLowerCase();
  const stem = lower.endsWith(".xapk") ? base.slice(0, -5) : lower.endsWith(".apk") ? base.slice(0, -4) : base;
  return `${stem || "app"}-android-auto.apk`;
}

function contentDisposition(filename) {
  const ascii = filename.replace(/[^\x20-\x7E]/g, "_").replace(/"/g, "");
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(filename)}`;
}

const upload = multer({
  storage: multer.diskStorage({
    destination(req, file, callback) {
      const id = crypto.randomUUID();
      const directory = path.join(paths.work, id);
      fs.mkdirSync(directory, { recursive: true });
      req.jobId = id;
      callback(null, directory);
    },
    filename(req, file, callback) {
      const xapk = (file.originalname || "").toLowerCase().endsWith(".xapk");
      callback(null, xapk ? "input.xapk" : "input.apk");
    },
  }),
  limits: { files: 1 },
  fileFilter(req, file, callback) {
    const name = (file.originalname || "").toLowerCase();
    if (!name.endsWith(".apk") && !name.endsWith(".xapk")) {
      callback(new Error("Choose an .apk or .xapk file."));
      return;
    }
    callback(null, true);
  },
});

function startJob(id, originalName, packageSuffix = "", gamepad = false, kind = "game") {
  const directory = path.join(paths.work, id);
  const xapk = (originalName || "").toLowerCase().endsWith(".xapk");
  const inputPath = path.join(directory, xapk ? "input.xapk" : "input.apk");
  const header = Buffer.alloc(2);
  const fd = fs.openSync(inputPath, "r");
  fs.readSync(fd, header, 0, 2, 0);
  fs.closeSync(fd);
  if (header.toString("utf8") !== "PK") {
    fs.rmSync(directory, { recursive: true, force: true });
    const error = new Error("That file is not an APK or XAPK.");
    error.status = 400;
    throw error;
  }

  const job = {
    id,
    status: "running",
    step: "decode",
    log: [],
    error: null,
    changes: [],
    packageName: "",
    downloadName: safeDownloadName(originalName),
    originalName: path.basename(originalName || "app.apk"),
    format: "apk",
    kind: kind === "navigation" ? "navigation" : "game",
    size: 0,
    progress: null,
    progressLabel: "",
    createdAt: Date.now(),
    outputPath: path.join(directory, "signed.apk"),
  };
  jobs.set(id, job);

  const convert = xapk ? runXapkPipeline : runPipeline;
  convert({
    inputPath,
    outputPath: job.outputPath,
    log: job.log,
    kind: job.kind,
    packageSuffix,
    gamepad,
    onStep(step) {
      job.step = step;
    },
    onProgress(percent, label) {
      job.progress = percent;
      job.progressLabel = label || "Working";
    },
  })
    .then((result) => {
      job.status = "done";
      job.step = "sign";
      job.progress = null;
      job.progressLabel = "";
      job.changes = result.changes;
      job.packageName = result.packageName;
      job.format = result.format || job.format;
      job.size = result.size;
      fs.rmSync(inputPath, { force: true });
    })
    .catch((error) => {
      job.status = "error";
      job.progress = null;
      job.progressLabel = "";
      job.error = "This package could not be converted.";
      job.log.push(job.error);
    });

  return job;
}

function purgeOldJobs() {
  const now = Date.now();
  for (const [id, job] of jobs) {
    if (job.status === "running") continue;
    if (now - job.createdAt < JOB_TTL_MS) continue;
    fs.rmSync(path.join(paths.work, id), { recursive: true, force: true });
    jobs.delete(id);
  }
  for (const [sessionId, pending] of pendingDonations) {
    if (pending.jobId || now - pending.createdAt < JOB_TTL_MS) continue;
    if (pending.hasFile) fs.rmSync(path.join(paths.work, pending.id), { recursive: true, force: true });
    pendingDonations.delete(sessionId);
  }
}

async function startInstalledJob(packageName, packageSuffix, gamepad, kind) {
  const id = crypto.randomUUID();
  const directory = path.join(paths.work, id);
  fs.mkdirSync(directory, { recursive: true });
  try {
    const packed = await pullInstalledApp(packageName, directory, packageSuffix);
    return startJob(id, packed.fileName, packageSuffix, gamepad, kind);
  } catch (error) {
    fs.rmSync(directory, { recursive: true, force: true });
    throw error;
  }
}

const app = express();
app.use(express.json());
app.use(express.static(path.join(paths.root, "public")));

app.get("/api/health", (req, res) => {
  try {
    const java = assertTools();
    res.json({ ok: true, java: java.major, apktool: "3.0.3" });
  } catch (error) {
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.post("/api/jobs", (req, res) => {
  upload.single("apk")(req, res, (error) => {
    if (error) {
      res.status(400).json({ error: error.message || "Upload failed." });
      return;
    }
    if (!req.file) {
      res.status(400).json({ error: "Choose an APK to upload." });
      return;
    }
    try {
      const job = startJob(req.jobId, req.file.originalname, packageSuffixFrom(req.body?.rename), flagFrom(req.body?.gamepad), kindFrom(req.body?.kind));
      res.status(202).json(publicJob(job));
    } catch (startError) {
      res.status(startError.status || 500).json({ error: startError.message });
    }
  });
});

app.get("/api/device/apps", async (req, res) => {
  try {
    res.json({ apps: await listDeviceApps() });
  } catch (error) {
    res.status(500).json({ error: error.message || "The installed apps could not be read." });
  }
});

app.post("/api/device/jobs", async (req, res) => {
  const packageName = String(req.body?.packageName || "");
  if (!/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/.test(packageName)) {
    res.status(400).json({ error: "Choose an installed app." });
    return;
  }
  try {
    const job = await startInstalledJob(packageName, packageSuffixFrom(req.body?.rename), flagFrom(req.body?.gamepad), kindFrom(req.body?.kind));
    res.status(202).json(publicJob(job));
  } catch (error) {
    res.status(error.status || 500).json({ error: error.message || "That app could not be read." });
  }
});

app.post("/api/donation/checkout", (req, res) => {
  upload.single("apk")(req, res, async (error) => {
    if (error) {
      res.status(400).json({ error: error.message || "Upload failed." });
      return;
    }
    const packageName = String(req.body?.packageName || "");
    const hasPackage = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/.test(packageName);
    if (!req.file && !hasPackage) {
      res.status(400).json({ error: "Choose an APK to upload." });
      return;
    }
    if (kindFrom(req.body?.kind) !== "navigation") {
      if (req.jobId) fs.rmSync(path.join(paths.work, req.jobId), { recursive: true, force: true });
      res.status(400).json({ error: "A donation is only asked for navigation." });
      return;
    }
    const pendingId = req.jobId || crypto.randomUUID();
    try {
      const session = await createDonationCheckout(`${req.protocol}://${req.get("host")}`, pendingId);
      pendingDonations.set(session.id, {
        id: pendingId,
        sessionId: session.id,
        originalName: req.file?.originalname || "",
        packageName: hasPackage ? packageName : "",
        rename: flagFrom(req.body?.rename),
        gamepad: flagFrom(req.body?.gamepad),
        hasFile: Boolean(req.file),
        createdAt: Date.now(),
        jobId: "",
      });
      savePendingDonations();
      res.json({ url: session.url });
    } catch (checkoutError) {
      if (req.jobId) fs.rmSync(path.join(paths.work, req.jobId), { recursive: true, force: true });
      res.status(checkoutError.status || 502).json({ error: checkoutError.message || "Stripe could not start the donation." });
    }
  });
});

app.post("/api/donation/continue", async (req, res) => {
  try {
    const session = await readDonationCheckout(String(req.body?.sessionId || ""));
    if (session.payment_status !== "paid") {
      res.status(402).json({ error: "The donation is not complete." });
      return;
    }
    const pending = pendingDonations.get(session.id);
    if (!pending || session.metadata?.pending !== pending.id) {
      res.status(404).json({ error: "The donation session was not found." });
      return;
    }
    if (pending.jobId && jobs.get(pending.jobId)) {
      res.status(202).json(publicJob(jobs.get(pending.jobId)));
      return;
    }
    const suffix = packageSuffixFrom(pending.rename);
    const job = pending.hasFile
      ? startJob(pending.id, pending.originalName, suffix, pending.gamepad, "navigation")
      : await startInstalledJob(pending.packageName, suffix, pending.gamepad, "navigation");
    pending.jobId = job.id;
    savePendingDonations();
    res.status(202).json(publicJob(job));
  } catch (error) {
    res.status(error.status || 500).json({ error: error.message || "The package could not be started." });
  }
});

app.get("/api/jobs/:id", (req, res) => {
  if (!ID_PATTERN.test(req.params.id)) {
    res.status(404).json({ error: "Job not found." });
    return;
  }
  const job = jobs.get(req.params.id);
  if (!job) {
    res.status(404).json({ error: "Job not found." });
    return;
  }
  res.json(publicJob(job));
});

app.get("/api/jobs/:id/download", (req, res) => {
  if (!ID_PATTERN.test(req.params.id)) {
    res.status(404).json({ error: "Job not found." });
    return;
  }
  const job = jobs.get(req.params.id);
  if (!job || job.status !== "done" || !fs.existsSync(job.outputPath)) {
    res.status(404).json({ error: "The signed package is not ready." });
    return;
  }
  // const xapk = job.downloadName.toLowerCase().endsWith(".xapk");
  const outputPath = job.outputPath;
  res.setHeader("Content-Type", "application/vnd.android.package-archive");
  res.setHeader("Content-Disposition", contentDisposition(job.downloadName));
  res.setHeader("Cache-Control", "no-store");
  res.sendFile(outputPath, (error) => {
    // Keep the finished package so it can be downloaded more than once.
    // Expired jobs are removed by purgeOldJobs after JOB_TTL_MS.
  });
});

app.post("/api/jobs/:id/install", async (req, res) => {
  if (!ID_PATTERN.test(req.params.id)) {
    res.status(404).json({ error: "Job not found." });
    return;
  }
  const job = jobs.get(req.params.id);
  if (!job || job.status !== "done" || !fs.existsSync(job.outputPath)) {
    res.status(404).json({ error: "The signed package is not ready." });
    return;
  }
  try {
    if (job.format === "xapk") {
      res.status(400).json({ error: "Choose an APK to upload." });
      return;
    }
    // const result = job.format === "xapk"
    //   ? await installXapkOnDevice(job.outputPath, `automaker-${job.id}`, job.packageName)
    //   : await installApkOnDevice(job.outputPath, `automaker-${job.id}.apk`, job.packageName);
    await pushSidecars(path.join(paths.work, job.id, "sidecars"));
    const result = await installApkOnDevice(job.outputPath, `automaker-${job.id}.apk`, job.packageName);
    if (!result.installed) {
      res.status(500).json({ error: "The app was not installed." });
      return;
    }
    res.json({
      installed: true,
      message: "Installed on the phone. The download is still available.",
    });
  } catch (error) {
    res.status(500).json({ error: error.toolOutput || error.message || "The phone installer could not be opened." });
  }
});

function packageSuffixFrom(value) {
  return flagFrom(value) ? PATCHED_SUFFIX : "";
}

function kindFrom(value) {
  return value === "navigation" ? "navigation" : "game";
}

function flagFrom(value) {
  return value === true || value === "true" || value === "1" || value === "on";
}

function shareUrls(port) {
  const urls = [`http://127.0.0.1:${port}`];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) urls.push(`http://${entry.address}:${port}`);
    }
  }
  return urls;
}

setInterval(purgeOldJobs, 10 * 60 * 1000).unref();

const server = app.listen(PORT, HOST, () => {
  try {
    const java = findJava();
    assertTools();
    for (const url of shareUrls(PORT)) console.log(`Android Auto Maker is running at ${url}`);
    console.log(`Java ${java.major} · apktool 3.0.3 · apksigner · zipalign · sessions run together`);
  } catch (error) {
    console.error(error.message);
  }
});

server.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
