# onnxruntime

`ai.onnxruntime.*`, JNI-accessible.

Why: the hero backdrop framer runs face detection through ONNX Runtime (`HeroBackdropFramer`,
models in `<install>\resources\`), and its Java API is driven from native code via JNI. The native
library itself comes in through the `ai/onnxruntime/native/win-x64/**` glob in `resources/`.
