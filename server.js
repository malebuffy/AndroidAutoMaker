import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import express from "express";
import multer from "multer";
import { runPipeline, STEPS } from "./lib/pipeline.js";
import { assertTools, findJava, paths } from "./lib/tools.js";
import { installApkOnDevice } from "./lib/deviceInstall.js";
// import { installXapkOnDevice } from "./lib/deviceInstall.js";
import { runXapkPipeline } from "./lib/xapk.js";

const PORT = Number(process.env.PORT) || 4173;
const HOST = process.env.HOST || "0.0.0.0";
const JOB_TTL_MS = 60 * 60 * 1000;
const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const jobs = new Map();

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
    kind: "game",
    log: [],
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

function startJob(id, originalName) {
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
    kind: "game",
    size: 0,
    createdAt: Date.now(),
    outputPath: path.join(directory, "signed.apk"),
  };
  jobs.set(id, job);

  const convert = xapk ? runXapkPipeline : runPipeline;
  convert({
    inputPath,
    outputPath: job.outputPath,
    log: job.log,
    onStep(step) {
      job.step = step;
    },
  })
    .then((result) => {
      job.status = "done";
      job.step = "sign";
      job.changes = result.changes;
      job.packageName = result.packageName;
      job.format = result.format || job.format;
      job.size = result.size;
      fs.rmSync(inputPath, { force: true });
    })
    .catch((error) => {
      job.status = "error";
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
}

const app = express();
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
      const job = startJob(req.jobId, req.file.originalname);
      res.status(202).json(publicJob(job));
    } catch (startError) {
      res.status(startError.status || 500).json({ error: startError.message });
    }
  });
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
  const sessionId = job.id;
  res.setHeader("Content-Type", "application/vnd.android.package-archive");
  res.setHeader("Content-Disposition", contentDisposition(job.downloadName));
  res.setHeader("Cache-Control", "no-store");
  res.sendFile(outputPath, (error) => {
    if (error) return;
    removeSession(sessionId);
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

function removeSession(id, attempt = 0) {
  const directory = path.resolve(paths.work, id);
  const root = path.resolve(paths.work);
  if (directory !== root && !directory.startsWith(`${root}${path.sep}`)) return;
  jobs.delete(id);
  fs.rm(directory, { recursive: true, force: true }, (error) => {
    if (error && attempt < 8) setTimeout(() => removeSession(id, attempt + 1), 250);
  });
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
