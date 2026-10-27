# Prove strumentate dei motori dei volti (Fase 8, P2)

I test stanno in `core-ai/src/androidTest/kotlin/dev/pampa/mycloudgallery/core/ai/faces/`. **Sono scritti e compilano; il pacchetto P2 non li ha eseguiti** (niente telefono né
emulatore): li esegue la finestra F104 su emulatore x86_64, S25 e Tab S9. Questa cartella (non compilata, non nell'APK di prova) ha gli arnesi per prepararli.

## Che cosa serve sul dispositivo

| Cosa | Dove | Note |
|---|---|---|
| I due modelli | negli asset dell'APK di prova (`assets/faces/`, quelli di `core-ai`) | niente da copiare: `FacesDevice.files()` li estrae con il gestore dei modelli (origine `Bundled`, nessuna rete) e `ModelFiles.open` ne verifica dimensione e SHA-256 a ogni apertura |
| Le composizioni di P1 (immagini) | `/data/local/tmp/mcg-faces/img/` | **fuori dal repository**: ritratti di pubblico dominio incollati su sfondi senza volti; senza, le prove con volti veri si saltano (`Assume`) |
| I riferimenti numerici | `/data/local/tmp/mcg-faces/refs.txt` | solo numeri, esportati dall'ONNX di P1 con `esporta_riferimenti.py` |

Le prove che non hanno bisogno di volti (ingressi ostili, isolamento dei guasti, forma dei modelli, misure di tempo e memoria) girano da sole.

## 1. Sul PC: i riferimenti e le immagini (una volta)

Serve l'ambiente di P1 (`C:\VibeCoded Projects\mcg-models\faces`, con `venv\conv`, gli ONNX in `onnx\` e le composizioni in `out\composizioni\`).

```powershell
$py   = "C:\VibeCoded Projects\mcg-models\faces\venv\conv\Scripts\python.exe"
$root = "C:\VibeCoded Projects\mcg-models\faces"
$dest = "$root\out\dispositivo"        # FUORI dal repository (lo script si rifiuta altrimenti)
& $py -I core-ai\src\androidTest\faces\esporta_riferimenti.py --root $root --dest $dest --serie A B C --max 30 --tensori 6
```

Scrive `$dest\refs.txt`, `$dest\img\<serie>-img-NNN.png` e (con `--tensori N`) `$dest\tensori-reali.txt`: le uscite grezze **vere** di YuNet per le prime N
immagini, nel formato delle fixture della JVM. Copiandolo in `core-ai/src/test/resources/faces/` la prova `YuNetDecoderTest` lo prende da sola (sono numeri,
non immagini: `FaceFixturesGuardTest` lo controlla). P1 non aveva lasciato tensori grezzi; le fixture del repository sono per ora **ricostruite** dai numeri
di P1 (`genera_riferimenti.py`), con l'atteso del decodificatore di riferimento di P1.

## 2. Sul dispositivo (emulatore, S25, Tab S9: uno alla volta)

```powershell
adb push "$dest\." /data/local/tmp/mcg-faces/
```

Regole: un solo dispositivo collegato per volta (`adb devices`); l'emulatore si avvia solo per la prova e si spegne subito dopo (non tocca emulatori che non sono suoi).

## 3. Lanciare i test

Dalla radice del repository, con il posto di Gradle preso come per ogni build (`gradle-slot.ps1 -Acquire/-Release`) e `GRADLE_OPTS=-Dorg.gradle.workers.max=2`:

```powershell
# Tutta la suite dei volti
.\gradlew.bat --max-workers=2 :core-ai:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.pampa.mycloudgallery.core.ai.faces

# Una classe sola
.\gradlew.bat --max-workers=2 :core-ai:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.core.ai.faces.FaceParityDeviceTest
```

| Classe | Che cosa prova | Servono i file di P1 |
|---|---|---|
| `FaceInputsDeviceTest` | `ModelInputs.forFaces`: pixel sRGB raddrizzati e ridotti, trasparenza sul bianco, fotogramma; **ingressi ostili che non arrivano mai al decodificatore** (contatore `decodeAttempts` come in P4 della Fase 6); mutazioni a caso | no |
| `FaceEnginesContractDeviceTest` | la suite `FaceEnginesContract` sui modelli veri; sfondi senza volti; seconda passata a quattro tasselli; modello alterato (si riestrae dall'APK); modello diverso (`MODEL_UNAVAILABLE`) | la prima prova sì |
| `FaceParityDeviceTest` | **parità con i riferimenti di P1**: riquadri IoU >= 0,95 e cinque punti entro 1 px, **coseno >= 0,999** dei vettori; richiamo dei volti di 24-47 px e da 48 px | sì |
| `FaceFaultIsolationDeviceTest` | un interprete vero che lancia su un volto non fa perdere gli altri; tre guasti di fila; guasto del rilevatore; rilascio e memoria | no |
| `FaceMeasurementsDeviceTest` | p50 e p95 di YuNet a 1024 (e a 4 tasselli), di SFace per volto, avvio a freddo e a caldo, verifica delle impronte, PSS, foto al secondo e `facesPerSecond` suggerito | solo l'ultima prova |

## 4. Leggere i numeri

Una riga JSON per misura nel log (`adb logcat -s P2Measure`) e in `files/p2-measures.jsonl` dell'APK di prova:

```powershell
adb shell run-as dev.pampa.mycloudgallery.core.ai.test cat files/p2-measures.jsonl
```

Poi: i numeri vanno in `docs/decisioni.md` ("Fase 8 P2", tabella "Misure sul dispositivo") con dispositivo e data, `DeviceMeasures.facesPerSecond` si aggiorna con
`facesPerSecond_suggerito` della prova `fotoAlSecondoEFacesPerSecond` (S25; il Tab S9 ha il suo), e un test che cade si segnala prima di toccare soglie o codice.
