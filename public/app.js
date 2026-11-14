const chooseInstalled = document.querySelector("#choose-installed");
const renamePackage = document.querySelector("#rename-package");
const gamepad = document.querySelector("#gamepad");
const appPicker = document.querySelector("#app-picker");
const closeApps = document.querySelector("#close-apps");
const appSearch = document.querySelector("#app-search");
const appPickerStatus = document.querySelector("#app-picker-status");
const appList = document.querySelector("#app-list");
const fileInput = document.querySelector("#file");
const dropzone = document.querySelector("#dropzone");
const fileRow = document.querySelector("#file-row");
const fileName = document.querySelector("#file-name");
const fileSize = document.querySelector("#file-size");
const clearFile = document.querySelector("#clear-file");
const convertButton = document.querySelector("#convert");
const statusCard = document.querySelector("#status");
const showLogButton = document.querySelector("#show-log");
const extractPanel = document.querySelector("#extract");
const extractLabel = document.querySelector("#extract-label");
const logView = document.querySelector("#log");
let logOpen = false;
const statusTitle = document.querySelector("#status-title");
const statusCopy = document.querySelector("#status-copy");
const progressBar = document.querySelector("#progress-bar");
const steps = document.querySelector("#steps");
const changeResult = document.querySelector("#change-result");
const downloadLink = document.querySelector("#download");
const installButton = document.querySelector("#install");
const installWarning = document.querySelector("#install-warning");
const installNote = document.querySelector("#install-note");
const tools = document.querySelector("#tools");
const snackbar = document.querySelector("#snackbar");
const donationDialog = document.querySelector("#donation");
const donateButton = document.querySelector("#donate");
const donateSkipButton = document.querySelector("#donate-skip");

const STEP_FALLBACK = [
  { id: "decode", label: "Prepare" },
  { id: "patch", label: "Update" },
  { id: "build", label: "Build" },
  { id: "sign", label: "Finish" },
];

let selectedFile = null;
let selectedInstalled = null;
let installedApps = [];
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
  selectedInstalled = null;
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
  selectedInstalled = null;
  fileRow.hidden = true;
  dropzone.hidden = false;
  convertButton.disabled = true;
});

function showApps(query) {
  const needle = query.trim().toLowerCase();
  const visible = installedApps.filter((app) => app.packageName.toLowerCase().includes(needle) || app.label.toLowerCase().includes(needle));
  appList.replaceChildren();
  for (const app of visible) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = app.label === app.packageName ? app.packageName : `${app.label} — ${app.packageName}`;
    button.addEventListener("click", () => {
      selectedInstalled = app.packageName;
      selectedFile = null;
      fileName.textContent = app.label;
      fileSize.textContent = app.packageName;
      fileRow.hidden = false;
      dropzone.hidden = true;
      convertButton.disabled = false;
      appPicker.hidden = true;
    });
    item.append(button);
    appList.append(item);
  }
  appPickerStatus.hidden = visible.length > 0;
  if (visible.length === 0 && installedApps.length > 0) appPickerStatus.textContent = "No apps match that search.";
}

chooseInstalled.addEventListener("click", async () => {
  appPicker.hidden = false;
  appSearch.value = "";
  appList.replaceChildren();
  appPickerStatus.hidden = false;
  appPickerStatus.textContent = "Reading apps on the phone…";
  try {
    const response = await fetch("/api/device/apps");
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || "The installed apps could not be read.");
    installedApps = payload.apps || [];
    appPickerStatus.textContent = installedApps.length ? "" : "No installed apps were found.";
    showApps("");
  } catch (error) {
    appPickerStatus.textContent = error.message || "The installed apps could not be read.";
  }
});

closeApps.addEventListener("click", () => {
  appPicker.hidden = true;
});

function showRenameNote() {
  const sideBySide = renamePackage.checked;
  const navigation = document.querySelector('input[name="kind"]:checked')?.value === "navigation";
  const availability = navigation
    ? "The patched app uses Shizuku or root to open its screen in Android Auto."
    : "A parked game needs a phone on Android 15 or newer.";
  installWarning.textContent = sideBySide
    ? `The patched package installs next to the original app. ${availability}`
    : `Uninstall the original app first. ${availability}`;
  installNote.textContent = sideBySide
    ? "The patched package can stay installed beside the original."
    : "Uninstall the original app first.";
}

renamePackage.addEventListener("change", showRenameNote);
for (const choice of document.querySelectorAll('input[name="kind"]')) {
  choice.addEventListener("change", showRenameNote);
}

appSearch.addEventListener("input", () => showApps(appSearch.value));

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
  progressBar.style.width = "";
  const busy = job.status === "running" || job.status === "queued";
  const extracting = typeof job.progress === "number" && job.progress >= 0;
  if (extracting) {
    progressBar.classList.add("determinate");
    progressBar.style.width = `${job.progress}%`;
  } else if (busy) {
    progressBar.classList.add("indeterminate");
  }
  if (job.status === "done") progressBar.classList.add("done");
  if (job.status === "error") progressBar.classList.add("failed");

  if (job.status === "running") {
    statusTitle.textContent = "Working";
    const active = items.find((step) => step.state === "active");
    const phaseLabel = job.progressLabel || "Working";
    statusCopy.textContent = extracting ? `${phaseLabel} ${job.progress}%` : (active ? active.label : "Starting");
  } else if (job.status === "done") {
    statusTitle.textContent = "Ready to install";
    const details = [job.packageName, job.size ? formatSize(job.size) : ""].filter(Boolean);
    statusCopy.textContent = details.join(" · ");
  } else {
    statusTitle.textContent = "Could not convert this package";
    statusCopy.textContent = "This package could not be converted.";
  }

  extractPanel.hidden = !extracting;
  if (extracting) extractLabel.textContent = `${job.progressLabel || "Working"} ${job.progress}%`;

  showLogButton.hidden = false;
  showLogButton.textContent = logOpen ? "Hide log" : "Show log";
  const logText = (job.log || []).join("\n");
  if (logView.textContent !== logText) {
    logView.textContent = logText;
    if (logOpen) logView.scrollTop = logView.scrollHeight;
  }
  logView.hidden = !logOpen;

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

  convertButton.disabled = busy || (!selectedFile && !selectedInstalled);
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

showLogButton.addEventListener("click", () => {
  logOpen = !logOpen;
  logView.hidden = !logOpen;
  showLogButton.textContent = logOpen ? "Hide log" : "Show log";
  if (logOpen) logView.scrollTop = logView.scrollHeight;
});

function selectedKind() {
  return document.querySelector('input[name="kind"]:checked')?.value === "navigation" ? "navigation" : "game";
}

async function startConversion() {
  clearTimeout(pollTimer);
  convertButton.disabled = true;
  convertButton.textContent = "Working…";
  statusCard.hidden = false;
  statusTitle.textContent = "Uploading";
  statusCopy.textContent = selectedInstalled || selectedFile.name;
  progressBar.className = "progress-bar indeterminate";
  changeResult.hidden = true;
  downloadLink.hidden = true;
  installButton.hidden = true;

  const rename = renamePackage.checked;
  const kind = selectedKind();
  let response;
  try {
    if (selectedInstalled) {
      response = await fetch("/api/device/jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ packageName: selectedInstalled, rename, gamepad: gamepad.checked, kind }),
      });
    } else {
      const body = new FormData();
      body.append("apk", selectedFile, selectedFile.name);
      body.append("kind", kind);
      if (rename) body.append("rename", "1");
      if (gamepad.checked) body.append("gamepad", "1");
      response = await fetch("/api/jobs", { method: "POST", body });
    }
    const payload = await response.json();
    if (!response.ok) {
      showSnack(payload.error || "Upload failed.");
      convertButton.disabled = !selectedFile && !selectedInstalled;
      convertButton.textContent = "Create Android Auto package";
      return;
    }
    sessionStorage.setItem("autoMakerJob", payload.id);
    renderJob(payload);
    poll(payload.id);
  } catch (error) {
    showSnack("The server could not be reached.");
    convertButton.disabled = !selectedFile && !selectedInstalled;
    convertButton.textContent = "Create Android Auto package";
  }
}

convertButton.addEventListener("click", () => {
  if ((!selectedFile && !selectedInstalled) || convertButton.disabled) return;
  if (selectedKind() === "navigation") {
    donationDialog.hidden = false;
    return;
  }
  startConversion();
});

donateSkipButton.addEventListener("click", () => {
  donationDialog.hidden = true;
  startConversion();
});

donateButton.addEventListener("click", async () => {
  if (donateButton.disabled) return;
  donateButton.disabled = true;
  donateSkipButton.disabled = true;
  const body = new FormData();
  body.append("kind", "navigation");
  if (renamePackage.checked) body.append("rename", "1");
  if (gamepad.checked) body.append("gamepad", "1");
  if (selectedInstalled) body.append("packageName", selectedInstalled);
  else body.append("apk", selectedFile, selectedFile.name);
  try {
    const response = await fetch("/api/donation/checkout", { method: "POST", body });
    const payload = await response.json();
    if (!response.ok || !payload.url) {
      showSnack(payload.error || "The donation page could not be opened.");
      donateButton.disabled = false;
      donateSkipButton.disabled = false;
      return;
    }
    window.location.assign(payload.url);
  } catch (error) {
    showSnack("The server could not be reached.");
    donateButton.disabled = false;
    donateSkipButton.disabled = false;
  }
});

async function continueDonation(sessionId) {
  donationDialog.hidden = true;
  statusCard.hidden = false;
  statusTitle.textContent = "Uploading";
  statusCopy.textContent = "Starting the package after the donation.";
  progressBar.className = "progress-bar indeterminate";
  convertButton.disabled = true;
  convertButton.textContent = "Working…";
  try {
    const response = await fetch("/api/donation/continue", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId }),
    });
    const payload = await response.json();
    if (!response.ok) {
      showSnack(payload.error || "The package could not be started.");
      convertButton.disabled = !selectedFile && !selectedInstalled;
      convertButton.textContent = "Create Android Auto package";
      return;
    }
    sessionStorage.setItem("autoMakerJob", payload.id);
    renderJob(payload);
    poll(payload.id);
  } catch (error) {
    showSnack("The server could not be reached.");
    convertButton.disabled = !selectedFile && !selectedInstalled;
    convertButton.textContent = "Create Android Auto package";
  }
}

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

const donationReturn = new URLSearchParams(window.location.search);
const donationSession = donationReturn.get("session_id");
if (donationReturn.get("donation") === "success" && donationSession) {
  history.replaceState({}, "", window.location.pathname);
  continueDonation(donationSession);
} else if (donationReturn.get("donation") === "cancel") {
  history.replaceState({}, "", window.location.pathname);
  showSnack("The donation was not completed. You can create the package without donating.");
}

const savedJob = sessionStorage.getItem("autoMakerJob");
if (savedJob && donationReturn.get("donation") !== "success") poll(savedJob);
