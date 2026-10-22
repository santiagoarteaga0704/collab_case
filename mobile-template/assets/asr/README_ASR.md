# Modelo Whisper para sherpa-onnx

Descarga el modelo oficial `sherpa-onnx-whisper-tiny` desde los releases de
`k2-fsa/sherpa-onnx` y coloca aquí, conservando exactamente estos nombres:

- `encoder.int8.onnx`
- `decoder.int8.onnx`
- `tokens.txt`

Debe ser la variante multilingual tiny INT8. Los modelos se empaquetan como
assets y se copian al almacenamiento privado de la aplicación al inicializar.
No se descargan archivos durante runtime.
