(function () {
  const button = document.getElementById("recordButton");
  const label = button.querySelector(".transport__label");
  const statusText = document.getElementById("statusText");
  const timerEl = document.getElementById("timer");
  const transcriptEl = document.getElementById("transcript");
  const led = document.getElementById("led");

  let mediaRecorder = null;
  let mediaStream = null;
  let chunks = [];
  let timerHandle = null;
  let startedAt = 0;

  // Browsers without MediaRecorder or getUserMedia can't run this page at all.
  // Disabling the button up front is clearer than letting the first click fail silently.
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.MediaRecorder) {
    button.disabled = true;
    setStatus("This browser can't record audio. Try a recent version of Chrome, Edge, or Firefox.", "error");
    return;
  }

  button.addEventListener("click", function () {
    if (button.dataset.state === "recording") {
      stopRecording();
    } else if (button.dataset.state === "idle") {
      startRecording();
    }
    // Clicks while "transcribing" are ignored: the button is mid-request and has nothing to do.
  });

  async function startRecording() {
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (err) {
      setStatus("Microphone access was denied. Allow microphone access and try again.", "error");
      return;
    }

    chunks = [];
    const mimeType = pickSupportedMimeType();
    mediaRecorder = mimeType
      ? new MediaRecorder(mediaStream, { mimeType: mimeType })
      : new MediaRecorder(mediaStream);

    mediaRecorder.addEventListener("dataavailable", function (event) {
      if (event.data && event.data.size > 0) {
        chunks.push(event.data);
      }
    });
    mediaRecorder.addEventListener("stop", handleRecordingStopped);

    mediaRecorder.start();
    setRecordingUI(true);
    setStatus("Recording…", "recording");
    startedAt = Date.now();
    timerHandle = setInterval(updateTimer, 200);
  }

  function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== "inactive") {
      mediaRecorder.stop();
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach(function (track) {
        track.stop();
      });
    }
    clearInterval(timerHandle);
    button.dataset.state = "transcribing";
    setRecordingUI(false);
    setStatus("Transcribing…", "processing");
  }

  async function handleRecordingStopped() {
    const blob = new Blob(chunks, { type: (mediaRecorder && mediaRecorder.mimeType) || "audio/webm" });
    const extension = blob.type.indexOf("wav") !== -1 ? "wav" : "webm";
    const formData = new FormData();
    formData.append("file", blob, "recording." + extension);

    try {
      const response = await fetch("/api/v1/transcribe", { method: "POST", body: formData });

      if (!response.ok) {
        const problem = await safeJson(response);
        throw new Error((problem && problem.message) || "Transcription request failed (status " + response.status + ").");
      }

      const result = await safeJson(response);
      transcriptEl.textContent = (result && result.text) || "(no speech detected)";
      setStatus("Ready to record", "success");
    } catch (err) {
      setStatus("Transcription failed: " + err.message, "error");
    } finally {
      button.dataset.state = "idle";
      timerEl.textContent = "00:00";
    }
  }

  function setRecordingUI(isRecording) {
    button.dataset.state = isRecording ? "recording" : button.dataset.state;
    button.setAttribute("aria-pressed", isRecording ? "true" : "false");
    label.textContent = isRecording ? "Stop recording" : "Start recording";
  }

  function updateTimer() {
    const elapsedSeconds = Math.floor((Date.now() - startedAt) / 1000);
    const minutes = String(Math.floor(elapsedSeconds / 60)).padStart(2, "0");
    const seconds = String(elapsedSeconds % 60).padStart(2, "0");
    timerEl.textContent = minutes + ":" + seconds;
  }

  function setStatus(message, tone) {
    statusText.textContent = message;
    statusText.dataset.tone = tone || "";
    led.dataset.tone =
      tone === "recording" ? "recording" :
      tone === "processing" ? "processing" :
      tone === "error" ? "error" :
      tone === "success" ? "ready" : "";
  }

  function pickSupportedMimeType() {
    const candidates = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"];
    return candidates.find(function (type) {
      return MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(type);
    });
  }

  async function safeJson(response) {
    try {
      return await response.json();
    } catch (err) {
      return null;
    }
  }
})();