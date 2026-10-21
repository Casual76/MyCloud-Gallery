# Regole R8 di :core-ai per chi lo usa (:app). Fase 6, P0 (docs/fase6-progetto.md D2, D44; "Fatto quando" di P0: APK di release con
# R8 e le regole `keep` delle tre librerie).

# --- LiteRT-LM (litertlm-android 0.18.1) --------------------------------------------------------------------------------------------
# L'AAR NON porta regole proprie. La parte nativa (liblitertlm_jni.so) chiama per nome classi, metodi e campi di Kotlin: le
# configurazioni (EmbeddingEngineConfig, Backend, InputData...) le legge via JNI, i callback (EmbeddingCallback, le *Callback interne)
# e le eccezioni (LiteRtLmJniException) le crea o le chiama da C++. Con nomi offuscati o membri tolti il motore fallisce a runtime
# (non alla build): si tiene tutto il pacchetto, che e' piccolo.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }

# kotlin-reflect e' TOLTO dalle dipendenze di litertlm-android (core-ai/build.gradle, docs/decisioni.md): lo usa solo l'API degli
# strumenti (`ToolKt`, `ReflectionTool`), che l'app non chiama (Phase6ArchitectureTest la vieta). Le classi tenute qui sopra lo
# nominano ancora: senza questa riga R8 si fermerebbe sulle classi mancanti. Solo i due pacchetti di kotlin-reflect, nient'altro.
-dontwarn kotlin.reflect.full.**
-dontwarn kotlin.reflect.jvm.**

# --- LiteRT (litert 2.3.0, runtime del ritaglio con MagicTouch) ---------------------------------------------------------------------
# Le regole dell'AAR tengono le classi segnate @UsedByReflection; come per LiteRT-LM la parte nativa (libLiteRt.so,
# liblitert_jni.so) chiama classi Kotlin per nome: si tiene il pacchetto.
-keep class com.google.ai.edge.litert.** { *; }
# L'AAR di litert porta anche l'API classica di TensorFlow Lite (`org.tensorflow.lite.Interpreter`, chiamata dalla stessa parte
# nativa): stessa regola.
-keep class org.tensorflow.lite.** { *; }

# `com.google.android.play:ai-delivery` (alpha) e' TOLTO dalle dipendenze di litert (core-ai/build.gradle): lo usa solo
# `com.google.ai.edge.litert.deployment.AiPackModelProvider` (modelli consegnati dal Play Store), che l'app non usa: il modello del
# ritaglio sta nell'APK (Q11). Solo il pacchetto delle AI pack di Play.
-dontwarn com.google.android.play.core.aipacks.**

# --- ML Kit (text-recognition 16.0.1) -----------------------------------------------------------------------------------------------
# Le librerie di ML Kit portano le loro regole (consumer-rules degli AAR). Qui niente: il cancello D41 e' codice nostro.
