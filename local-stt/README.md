# Collab CASE local STT

Servicio local de desarrollo para transcribir audio con faster-whisper. El modelo se carga una sola vez al iniciar.

## Inicio

```powershell
cd local-stt
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:WHISPER_MODEL="small"
$env:WHISPER_DEVICE="auto"
uvicorn app:app --host 127.0.0.1 --port 8090
```

`WHISPER_DEVICE=auto` intenta CUDA cuando CTranslate2 la detecta y usa CPU `int8` como fallback. En GPU se usa `float16`. El idioma predeterminado es espanol (`WHISPER_LANGUAGE=es`); se puede dejar vacio para autodeteccion.

El servicio acepta hasta 10 MB, utiliza un archivo temporal para que PyAV decodifique formatos como WebM/Opus y lo elimina al finalizar cada solicitud.
