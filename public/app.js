const fileInput = document.querySelector("#file");
const dropzone = document.querySelector("#dropzone");
const fileRow = document.querySelector("#file-row");
const fileName = document.querySelector("#file-name");
const fileSize = document.querySelector("#file-size");
const clearFile = document.querySelector("#clear-file");
const convertButton = document.querySelector("#convert");
const statusCard = document.querySelector("#status");
const statusTitle = document.querySelector("#status-title");
const statusCopy = document.querySelector("#status-copy");
const progressBar = document.querySelector("#progress-bar");
const steps = document.querySelector("#steps");
const changeResult = document.querySelector("#change-result");
const downloadLink = document.querySelector("#download");
const installButton = document.querySelector("#install");
const tools = document.querySelector("#tools");
const snackbar = document.querySelector("#snackbar");

const STEP_FALLBACK = [
  { id: "decode", label: "Prepare" },
  { id: "patch", label: "Update" },
  { id: "build", label: "Build" },
  { id: "sign", label: "Finish" },
];

let selectedFile = null;
let pollTimer = 0;
let snackTimer = 0;

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function showSnack(message) {
  snackbar.textContent = message;
  snackbar.classList.add("show");
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => snackbar.classList.remove("show"), 4200);
}

function setFile(file) {
  if (!file) return;
  const lowerName = file.name.toLowerCase();
  if (!lowerName.endsWith(".apk") && !lowerName.endsWith(".xapk")) {
    showSnack("Choose an .apk or .xapk file.");
    return;
  }
  selectedFile = file;
  fileName.textContent = file.name;
  fileSize.textContent = formatSize(file.size);
  fileRow.hidden = false;
  dropzone.hidden = true;
  convertButton.disabled = false;
}

fileInput.addEventListener("change", () => {
  setFile(fileInput.files[0]);
  fileInput.value = "";
});

clearFile.addEventListener("click", () => {
  selectedFile = null;
  fileRow.hidden = true;
  dropzone.hidden = false;
  convertButton.disabled = true;
});

["dragenter", "dragover"].forEach((eventName) => {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.add("dragover");
  });
});

["dragleave", "drop"].forEach((eventName) => {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.remove("dragover");
  });
});

dropzone.addEventListener("drop", (event) => {
  setFile(event.dataTransfer.files[0]);
});

function marker(state, index) {
  if (state === "done") return "✓";
  if (state === "error") return "!";
  return String(index + 1);
}

function renderJob(job) {
  statusCard.hidden = false;
  const items = job.steps?.length ? job.steps : STEP_FALLBACK;
  steps.innerHTML = "";
  items.forEach((step, index) => {
    const item = document.createElement("li");
    item.className = step.state || "pending";
    const mark = document.createElement("span");
    mark.className = "marker";
    mark.textContent = marker(step.state, index);
    item.append(mark, document.createTextNode(step.label));
    steps.append(item);
  });

  progressBar.className = "progress-bar";
  const busy = job.status === "running" || job.status === "queued";
  if (busy) progressBar.classList.add("indeterminate");
  if (job.status === "done") progressBar.classList.add("done");
  if (job.status === "error") progressBar.classList.add("failed");

  if (job.status === "running") {
    statusTitle.textContent = "Working";
    const active = items.find((step) => step.state === "active");
    statusCopy.textContent = active ? active.label : "Starting";
  } else if (job.status === "done") {
    statusTitle.textContent = "Ready to install";
    const details = [job.packageName, job.size ? formatSize(job.size) : ""].filter(Boolean);
    statusCopy.textContent = details.join(" · ");
  } else {
    statusTitle.textContent = "Could not convert this package";
    statusCopy.textContent = "This package could not be converted.";
  }

  changeResult.hidden = !(job.changes && job.changes.length);
  changeResult.innerHTML = "";
  (job.changes || []).forEach((change) => {
    const item = document.createElement("li");
    item.textContent = change;
    changeResult.append(item);
  });

  if (job.status === "done") {
    downloadLink.hidden = false;
    downloadLink.href = `/api/jobs/${job.id}/download`;
    downloadLink.download = job.downloadName || "android-auto.apk";
    downloadLink.textContent = `Download ${job.downloadName || "package"}`;
    installButton.hidden = false;
    installButton.disabled = false;
    installButton.dataset.jobId = job.id;
  } else {
    downloadLink.hidden = true;
    installButton.hidden = true;
  }

  convertButton.disabled = busy || !selectedFile;
  convertButton.textContent = job.status === "running" ? "Working…" : "Create Android Auto package";
}

async function poll(id) {
  const response = await fetch(`/api/jobs/${id}`);
  if (!response.ok) {
    showSnack("The conversion status could not be read.");
    return;
  }
  const job = await response.json();
  renderJob(job);
  if (job.status === "running" || job.status === "queued") {
    pollTimer = window.setTimeout(() => poll(id), 800);
    return;
  }
  sessionStorage.removeItem("autoMakerJob");
  if (job.status === "error") showSnack("This package could not be converted.");
}

convertButton.addEventListener("click", async () => {
  if (!selectedFile || convertButton.disabled) return;
  clearTimeout(pollTimer);
  convertButton.disabled = true;
  convertButton.textContent = "Working…";
  statusCard.hidden = false;
  statusTitle.textContent = "Uploading";
  statusCopy.textContent = selectedFile.name;
  progressBar.className = "progress-bar indeterminate";
  changeResult.hidden = true;
  downloadLink.hidden = true;
  installButton.hidden = true;

  const body = new FormData();
  body.append("apk", selectedFile, selectedFile.name);
  body.append("kind", "game");
  // body.append("kind", document.querySelector('input[name="kind"]:checked')?.value || "game");
  try {
    const response = await fetch("/api/jobs", { method: "POST", body });
    const payload = await response.json();
    if (!response.ok) {
      showSnack(payload.error || "Upload failed.");
      convertButton.disabled = !selectedFile;
      convertButton.textContent = "Create Android Auto package";
      return;
    }
    sessionStorage.setItem("autoMakerJob", payload.id);
    renderJob(payload);
    poll(payload.id);
  } catch (error) {
    showSnack("The server could not be reached.");
    convertButton.disabled = !selectedFile;
    convertButton.textContent = "Create Android Auto package";
  }
});

installButton.addEventListener("click", async () => {
  const id = installButton.dataset.jobId;
  if (!id || installButton.disabled) return;
  installButton.disabled = true;
  statusTitle.textContent = "Installing";
  progressBar.className = "progress-bar indeterminate";
  try {
    const response = await fetch(`/api/jobs/${id}/install`, { method: "POST" });
    const payload = await response.json();
    if (response.ok && payload.installed) {
      statusTitle.textContent = "Installed";
      statusCopy.textContent = "Installed on the phone.";
      progressBar.className = "progress-bar done";
      showSnack(payload.message);
    } else {
      statusTitle.textContent = "Ready to install";
      progressBar.className = "progress-bar done";
      showSnack(payload.error || payload.message || "The app was not installed.");
    }
  } catch (error) {
    statusTitle.textContent = "Ready to install";
    progressBar.className = "progress-bar done";
    showSnack("The server could not be reached.");
  } finally {
    installButton.disabled = false;
  }
});

fetch("/api/health")
  .then((response) => response.json())
  .then((health) => {
    tools.textContent = health.ok ? "Ready" : "Not ready";
  })
  .catch(() => {
    tools.textContent = "Tools could not be checked.";
  });

const savedJob = sessionStorage.getItem("autoMakerJob");
if (savedJob) poll(savedJob);
