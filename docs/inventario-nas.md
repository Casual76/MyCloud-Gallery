# Inventario e sicurezza: NAS e rete di casa (Fase 0.1)

> Raccolto il 12/10/2026 leggendo, in sola lettura, la dashboard del NAS e quella del FRITZ!Box, più l'app NAS Probe sul tablet (utente `test`).
> Nessun nome di file o percorso personale, nessun identificativo (seriali, MAC, email, nomi di dispositivi).

## 1. NAS: WD My Cloud EX2 Ultra
| Voce | Valore |
|---|---|
| Firmware | My Cloud OS 5, **5.33.102** |
| Stato | Integro, RAID 1 (dichiarato dall'utente), **2,42 TB liberi** |
| Rete | 1 Gbit/s, IPv4 192.168.178.58 (nome `MyCloudEX2Ultra.fritz.box`), **IPv6 disattivato**, jumbo frame off |
| Ora | NTP attivo, fuso Roma (utile come "ora di riferimento", §1 del piano) |
| Risparmio energetico | **Stand-by dischi ON**; **pianificazione alimentazione ON: acceso 08:00–22:00 tutti i giorni** (spento 22:00–08:00) |
| Timeout sessione web | 30 minuti (impostazione) |

### Share
| Share | Pubblica | Cestino | Usato | Note |
|---|---|---|---|---|
| `alessio` | no | OFF | 330,5 GB | area privata |
| `pampa` | no | OFF | 117,9 GB | area privata |
| `NAS` | **SÌ** | ON | 266,5 GB | di fatto l'area comune di famiglia |
| `Public` | **SÌ** | OFF | 24 KB | vuota |
| `test` | no | — | pochi MB | area di prova (0.7) |

In tutto circa **715 GB** di dati (non tutti media: il conteggio dei media arriverà dall'inventario in sola lettura dell'app di prova).

### Utenti e permessi
| Utente | Public | NAS | alessio | pampa | test |
|---|---|---|---|---|---|
| alessio | L/S | L/S | L/S | — | L/S |
| pampa | L/S | L/S | — | L/S | — |
| test | **L/S** | **L/S** | — | — | L/S |

Più l'utente `admin`. Quote: nessun limite.

### Servizi
- **SMB: solo SMB 2 e SMB 3** (SMB1 spento); autenticazione **solo NTLMv2**.
- SSH, FTP, NFS, SNMP, DNS dinamico: spenti. Accesso remoto alla dashboard: spento. Reindirizzamento HTTPS: acceso. Nessun port forwarding configurato dal NAS.
- Accesso alle app mobili/web attivo sulle share.
- **Accesso cloud WD: attivo e connesso** (account proprietario collegato all'utente `admin`).
- App installate: **Plex Media Server**, **Twonky Server** (DLNA). Indicizzano i media e vedrebbero anche le cartelle nascoste dell'indice: vedi E-I13.
- Disponibile ma non installata: app "USB Backups" di WD, un modo semplice per un backup esterno su disco USB.

## 2. Rete: FRITZ!Box 7530
| Voce | Valore |
|---|---|
| FRITZ!OS | 8.25, aggiornato, aggiornamenti automatici (livello III) |
| Connessione | ISP Aruba-by-Fastweb tramite LAN 1 (ONT esterno) |
| **IPv4 pubblico** | **NO: indirizzo 100.66.x.x = CGNAT** (non raggiungibile da Internet in IPv4) |
| IPv6 | **Sì, prefisso pubblico** delegato; in LAN c'è anche un prefisso ULA stabile (`fdd1:…`), utile per riconoscere "casa" (vedi `android.md`, scoperta 13) |
| Porte aperte da Internet | Solo servizi del FRITZ!Box (SIP, TR-069 del provider, manutenzione). **Nessuna abilitazione verso dispositivi della LAN.** |
| MyFRITZ! | registrato, nessuna abilitazione |
| Filtri | UPnP, NetBIOS, Teredo, WPAD attivi; liste di blocco IP di FRITZ! attive; modalità stealth spenta |
| VPN | nessuna connessione WireGuard risulta configurata |
| Utenti FRITZ!Box | un utente amministratore e un utente con solo "Contenuti NAS" |
| Wi-Fi | **WPA2 (non WPA3)**, **WPS acceso**, comunicazione tra dispositivi consentita, accesso ospite **spento** |
| Dispositivi in rete | decine, tra cui molti IoT (elettrodomestici, hub domotici, assistenti vocali, stampante, orologi, e-reader) **sulla stessa rete del NAS** |

## 3. Valutazione di sicurezza (priorità)
1. **Critico: share `NAS` e `Public` "pubbliche".** Su My Cloud OS 5, "Pubblico: ON" vuol dire che tutti gli utenti hanno accesso, e verosimilmente anche gli ospiti senza password, quindi qualunque dispositivo della LAN, compresi gli IoT. Su `NAS` ci sono 266 GB di dati di famiglia. Un dispositivo compromesso potrebbe leggerli o cancellarli, e il RAID 1 copierebbe il danno.
   **Azione consigliata:** "Pubblico: OFF" su `NAS` e `Public`, con accesso Lettura/Scrittura solo agli account di famiglia. Prima va controllato chi ci accede oggi senza credenziali (TV con Plex/Twonky, stampanti, altri PC).
2. **Alto: l'utente `test` ha Lettura/Scrittura su `NAS` e `Public`.** Non è isolato. **Azione:** negare `NAS` e `Public` a `test`, e a fine Fase 0 eliminarlo.
3. **Alto, per il piano: nessun IPv4 pubblico (CGNAT).** WireGuard dal FRITZ!Box sarà raggiungibile da fuori **solo in IPv6**. Funziona su reti mobili con IPv6 (gran parte degli operatori italiani), **non** su Wi-Fi esterni solo IPv4 (molti alberghi e locali). **Opzioni:** chiedere all'ISP un IPv4 pubblico (a volte gratuito, da verificare con Aruba), oppure accettare il limite.
4. **Medio: Wi-Fi.** Passare a **WPA2+WPA3** e **spegnere il WPS**.
5. **Medio: IoT sulla stessa rete del NAS.** Spostare gli IoT nella **rete ospiti** (isolata dalla LAN). La maggior parte funziona solo con Internet.
6. **Basso: accesso cloud WD attivo.** Se non si usa (l'app non lo userà), spegnerlo riduce l'esposizione verso Internet.
7. **Basso: indirizzo del NAS.** Verificare che nel FRITZ!Box sia attiva l'opzione "assegna sempre lo stesso indirizzo IPv4" per il NAS (non visibile in sola lettura dall'elenco).
8. **Informativo:**
   - **Positivi:** SMB1 e NTLMv1 spenti, nessuna porta aperta verso la LAN, dashboard remota spenta, FRITZ!OS aggiornato.
   - **Firma SMB:** il server la *abilita* ma non la *richiede*. La renderà obbligatoria l'app lato client (verificato: sessione firmata).
   - **Cifratura SMB3:** supportata (AES-128-GCM).
9. **Backup esterno assente (scelta dell'utente).** L'app WD "USB Backups" con un disco USB scollegato a rotazione resta l'opzione più economica, da riconsiderare.

## 4. Misure SMB dal tablet (Galaxy Tab S9, Wi-Fi 5 GHz 802.11ac, segnale −41 dBm, link 585/702 Mbit/s)
Utente `test`, share `test`, 6 file di prova (3 JPG, 1 PNG, 2 MP4, 167 MB in tutto).

| Misura | Sola firma | Firma + cifratura (AES-128-GCM) | Rapporto |
|---|---|---|---|
| Dialetto negoziato | SMB 3.1.1 | SMB 3.1.1 | — |
| Connessione / autenticazione | 594 / 125 ms | 720 / 205 ms | — |
| Lettura, 1 flusso | 9,9 MB/s | 7,6 MB/s | 0,77 |
| Lettura, 4 flussi | 21,9 MB/s | 15,8 MB/s | 0,72 |
| Scrittura (50 MB) | 7,8 MB/s | 7,3 MB/s | 0,94 |
| Rilettura con SHA-256 | 10,1 MB/s, hash OK | 7,4 MB/s, hash OK | 0,73 |
| Copia lato server (stessa share) | 140 MB/s, hash OK | — | — |
| Accesso casuale 64 KB su immagini | mediana 15–21 ms | — | — |
| Accesso casuale 1 MB su video | ~35–105 ms | — | — |
| Listing di una cartella | 44 ms (6 voci) | — | — |

**Lettura:**
- Il collo di bottiglia è il NAS (CPU debole con la firma), non il Wi-Fi: 4 flussi rendono 2,2 volte uno solo.
- La cifratura costa circa il 25–28% in lettura e il 6% in scrittura.
- La copia lato server nella stessa share è circa 18 volte più veloce della scrittura dal telefono. Tra share diverse non è ancora misurata.
- Ordini di grandezza: 1 GB di backup ≈ 2–2,5 minuti. Rileggere tutti i ~715 GB del NAS ≈ 9–13 ore con 4 flussi, quindi la preparazione iniziale dell'archivio va distribuita su più notti o giornate, coerente con il piano.
- Rete del tablet: nessuna VPN; domini di ricerca `fritz.box`; prefisso ULA presente; NAS raggiungibile in 4–18 ms.

### Galaxy S25 (Android 17, Wi-Fi 5 GHz 802.11ac, −40 dBm), stessa share di prova
| Misura | Sola firma | Firma + cifratura | Rapporto |
|---|---|---|---|
| Connessione / autenticazione | 30 / 43 ms | 588 / 61 ms | — |
| Lettura, 1 flusso | 10,7 MB/s | 9,0 MB/s | 0,84 |
| Lettura, 4 flussi | 22,9 MB/s | 16,3 MB/s | 0,71 |
| Scrittura (50 MB) | 9,5 MB/s | 9,0 MB/s | 0,94 |
| Copia lato server, stessa share | 124 MB/s, hash OK | — | — |
| **Copia lato server, tra share** (`test` → `Public`, con FSCTL_SRV_COPYCHUNK scritto a mano) | **148 MB/s, hash OK** | — | — |

**Lettura:**
- I numeri coincidono con quelli del tablet, a conferma che il limite è il NAS.
- **La copia lato server funziona anche tra share diverse**: spostamenti e condivisioni nell'area comune non fanno passare i byte dal telefono, ma serve la chiamata FSCTL diretta, perché smbj non la espone tra share.
- Il primo tentativo sull'S25 è fallito con `connection abort` perché l'app era finita in background mentre girava un'altra app (vedi `decisioni.md`, "Rete in background").

## 4-bis. Inventario dei media (sola lettura, account dell'utente, S25, 12/10)
| Share | File | GB | Cartelle | Prof. max | Scansione |
|---|---|---|---|---|---|
| `alessio` | 12.283 | 279,4 | 6 | 2 | **1,9 s** (struttura piatta; una cartella con **8.910 file**) |
| `NAS` | 20.102 | 272,6 | 2.356 | 6 | **45,7 s** (~19 ms per cartella) |

**`alessio`: quasi tutto media (12.244 file, 279 GB)**
- 8.723 JPG (mediana 1,8 MB), 1.573 PNG (probabilmente screenshot), 238 HEIC, 86 JPEG, 19 GIF, 7 DNG (RAW, ~64 MB l'uno), 3 WebP;
- **1.577 MP4 = 250 GB** (fino a 9,4 GB per file), 17 MOV, 1 WebM;
- date dei file dal 1979 (data non valida) al 2026.

**`NAS`: soprattutto musica**
- ~11.000 MP3, ~1.900 FLAC, M4A, WAV, cue/log/m3u: circa 150 GB di audio;
- media visivi: 5.285 JPG con **mediana 40 KB**, cioè in gran parte **copertine degli album** nelle cartelle musicali, 137 PNG, 20 JPEG, 12 BMP, 33 GIF;
- **371 MP4 = 96 GB** (mediana 129 MB) e **9 MKV = 20 GB** (fino a 13,5 GB): probabilmente film o registrazioni, non video di famiglia.

**Conseguenze per il piano:**
- Le foto e i video di famiglia raggiungibili dall'utente sono nell'ordine di **15–20 mila**, a cui si aggiunge la share `pampa` (117,9 GB, non inventariata). Il volume è sotto la stima iniziale. Con la NPU (~12 foto/s) il calcolo AI dell'intero archivio richiede meno di un'ora; il collo di bottiglia restano la rete e la preparazione dei video.
- **Le cartelle di musica e film non devono finire in galleria.** Servono un'esclusione predefinita delle copertine degli album (immagini piccole in cartelle con file audio) e la proposta, durante l'onboarding, di escludere le cartelle di musica e film. Sono due nuovi edge case (E-N14, E-N15).
- **I video pesano più di tutto il resto** (~350 GB): la preparazione tramite fotogrammi chiave senza scaricarli interi è indispensabile.
- Le cartelle con migliaia di file (E-N5) esistono davvero; la scansione incrementale resta veloce.

## 5. Ancora da raccogliere
- Inventario della share `pampa` (serve l'account del proprietario).
- Wake-on-LAN dopo lo spegnimento programmato (è una prova serale: il NAS si spegne alle 22:00).
- Misure via VPN (dopo la configurazione di WireGuard e la decisione su IPv4/IPv6).
- Le stesse misure sull'S25.
