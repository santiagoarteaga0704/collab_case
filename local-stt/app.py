import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

import ctranslate2
from dotenv import load_dotenv
from fastapi import FastAPI, File, HTTPException, UploadFile
from faster_whisper import WhisperModel

load_dotenv(Path(__file__).with_name(".env"), override=False)

MAX_AUDIO_BYTES = 10 * 1024 * 1024
MODEL_NAME = os.getenv("WHISPER_MODEL", "small")
LANGUAGE = os.getenv("WHISPER_LANGUAGE", "es") or None
DEVICE_SETTING = os.getenv("WHISPER_DEVICE", "auto").lower()
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")


def load_model():
    wants_cuda = DEVICE_SETTING in {"auto", "cuda"}
    if wants_cuda and ctranslate2.get_cuda_device_count() > 0:
        try:
            return WhisperModel(MODEL_NAME, device="cuda", compute_type=COMPUTE_TYPE), "cuda"
        except Exception as error:
            if DEVICE_SETTING == "cuda":
                print(f"CUDA no disponible; se usara CPU: {error}")
    return WhisperModel(MODEL_NAME, device="cpu", compute_type=COMPUTE_TYPE), "cpu"


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.model, app.state.device = load_model()
    print(f"Whisper model={MODEL_NAME} device={app.state.device} compute_type={COMPUTE_TYPE} language={LANGUAGE or 'auto'}")
    yield


app = FastAPI(title="Collab CASE local STT", lifespan=lifespan)


# Plain "def": FastAPI runs it in its thread pool. As "async def", the CPU-bound Whisper call
# blocked the event loop and the service could only answer one request at a time.
@app.post("/transcribe")
def transcribe(file: UploadFile = File(...)):
    if not file.content_type or not file.content_type.startswith("audio/"):
        raise HTTPException(status_code=415, detail="El archivo debe ser audio.")

    suffix = Path(file.filename or "recording.webm").suffix or ".webm"
    temp_path = None
    total = 0
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temporary:
            temp_path = temporary.name
            while chunk := file.file.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_AUDIO_BYTES:
                    raise HTTPException(status_code=413, detail="El audio supera el limite de 10 MB.")
                temporary.write(chunk)
        if total == 0:
            raise HTTPException(status_code=400, detail="El audio esta vacio.")

        segments, _ = app.state.model.transcribe(
            temp_path,
            language=LANGUAGE,
            vad_filter=True,
            beam_size=5,
        )
        text = " ".join(segment.text.strip() for segment in segments).strip()
        if not text:
            raise HTTPException(status_code=422, detail="No se detecto voz en la grabacion.")
        return {"text": text}
    finally:
        file.file.close()
        if temp_path:
            Path(temp_path).unlink(missing_ok=True)
