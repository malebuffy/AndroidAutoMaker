import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import express from "express";
import multer from "multer";
import { runPipeline, STEPS } from "./lib/pipeline.js";
import { assertTools, findJava, paths } from "./lib/tools.js";
import { runXapkPipeline } from "./lib/xapk.js";

const PORT = Number(process.env.PORT) || 4173;
const MAX_BYTES = 512 * 1024 * 1024;
const JOB_TTL_MS = 60 * 60 * 1000;
const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const jobs = new Map();
let activeJobId = null;

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
    log: job.log.slice(-80),
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
  const xapk = lower.endsWith(".xapk");
  const stem = xapk ? base.slice(0, -5) : lower.endsWith(".apk") ? base.slice(0, -4) : base;
  return `${stem || "app"}-android-auto.${xapk ? "xapk" : "apk"}`;
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
  limits: { fileSize: MAX_BYTES, files: 1 },
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
    format: xapk ? "xapk" : "apk",
    size: 0,
    createdAt: Date.now(),
    outputPath: path.join(directory, xapk ? "signed.xapk" : "signed.apk"),
  };
  jobs.set(id, job);
  activeJobId = id;

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
      job.error = error.toolOutput
        ? `${error.message}\n${error.toolOutput}`.trim()
        : error.message || "The package could not be converted.";
      job.log.push(job.error);
    })
    .finally(() => {
      if (activeJobId === id) activeJobId = null;
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
  if (activeJobId) {
    res.status(409).json({ error: "Another package is being converted. Wait for it to finish." });
    return;
  }
  upload.single("apk")(req, res, (error) => {
    if (error) {
      res.status(400).json({ error: error.message || "Upload failed." });
      return;
    }
    if (!req.file) {
      res.status(400).json({ error: "Choose an APK or XAPK to upload." });
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
  const xapk = job.downloadName.toLowerCase().endsWith(".xapk");
  res.setHeader("Content-Type", xapk ? "application/zip" : "application/vnd.android.package-archive");
  res.setHeader("Content-Disposition", contentDisposition(job.downloadName));
  res.setHeader("Cache-Control", "no-store");
  res.sendFile(job.outputPath);
});

setInterval(purgeOldJobs, 10 * 60 * 1000).unref();

const server = app.listen(PORT, "127.0.0.1", () => {
  try {
    const java = findJava();
    assertTools();
    console.log(`Android Auto Maker is running at http://127.0.0.1:${PORT}`);
    console.log(`Java ${java.major} · apktool 3.0.3 · apksigner · zipalign`);
  } catch (error) {
    console.error(error.message);
  }
});

server.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
