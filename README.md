# Home Famiglia

Una Home per Android TV, pensata per le Philips su piattaforma `org.droidtv`: una scena disegnata che
cambia con il periodo dell'anno e con il meteo, l'orologio, e sotto le righe che servono davvero.

- **Stagioni in automatico:** Natale dal 1° dicembre al 6 gennaio, Halloween dal 15 al 31 ottobre, e nel
  resto dell'anno un paesaggio che cambia colore con la stagione. Giorno e notte seguono il sole.
- **Meteo:** pioggia animata se piove. Neve solo a 3 °C o meno. Dati da Open-Meteo, ogni 30 minuti.
- **Ultimi contenuti:** quello che stavi guardando, con locandina e avanzamento, se l'app lo pubblica.
  OK riapre il titolo.
- **App preferite**, fino a 6.
- **Canali live**, con anteprima del canale selezionato.
- **Ingressi HDMI** con nome e icona a scelta.
- **Personalizza**, direttamente dalla TV: preferite e loro ordine, righe e loro ordine, nomi HDMI.

## Provato su

Philips TPM191E (`PH9M_EA_5599`, Android TV 12) e 65PUS8506/12 (`PH1M_EA_9970A`, Android TV 11). Lista dei
canali e anteprima usano componenti Philips: su altre TV quella riga semplicemente non compare.

## Installazione

Serve un computer sulla stessa rete con `adb`, JDK 17 e Android SDK 34 (`ANDROID_HOME`), e sulla TV il
debug USB attivo.

```sh
cp cosy.defaults.properties cosy.properties   # città, preferite, soglia della neve
TV_HOST=192.168.1.50 ./install.sh
```

Lo script compila, installa, concede il permesso per gli ultimi contenuti e imposta la Home. Il launcher
di serie ha la precedenza finché è attivo: va disattivato a parte (`pm disable-user --user 0
com.google.android.tvlauncher`), dopo aver controllato che la nuova Home parta. Per alleggerire la TV in
sicurezza e con un ripristino, vedi [cleanyourtv](https://github.com/Gathin23/cleanyourtv). Dopo il cambio
di Home può servire un riavvio perché il tasto Home la apra.

Al termine spegni il debug sulla TV: la porta 5555 non è cifrata.

## Impostazioni

`cosy.defaults.properties` contiene tutte le chiavi; le tue vanno in `cosy.properties`, che git ignora.
La tua città resta quindi fuori dal repository.

| Chiave | Cosa fa |
|---|---|
| `location.name`, `location.latitude`, `location.longitude` | Città per meteo, alba e tramonto. Senza, niente meteo e nessuna connessione a Open-Meteo |
| `snow_at_or_below` | Temperatura sotto cui nevica, predefinita 3 |
| `channels_count` | Quanti canali mostrare, predefinito 6 |
| `dock` | Preferite iniziali, poi modificabili dalla TV |
| `hidden` | App mai mostrate |

Per vedere una scena senza aspettare la data giusta:

```sh
adb shell am start -n com.famiglia.home/com.cosyhome.tv.HomeActivity --es season christmas --es fx snow --es phase night
```

Valori: `season` christmas, halloween, spring, summer, autumn, winter; `fx` clear, rain, snow; `phase` dawn,
day, dusk, night. `auto` torna al comportamento normale.

## Cosa esce dalla TV

Ogni 30 minuti una richiesta a `api.open-meteo.com` con la posizione arrotondata a circa un chilometro, e le
locandine degli ultimi contenuti scaricate dai server delle rispettive app. Nessun account e nessuna analisi.

Permessi: internet e `READ_TV_LISTINGS`, per leggere gli ultimi contenuti; quest'ultimo si concede via ADB.

## Tornare indietro

```sh
adb shell am broadcast -f 32 -n com.famiglia.home/com.cosyhome.tv.HomeSwitch -a com.famiglia.home.HOME --ez on false
adb shell pm enable com.google.android.tvlauncher
```

## Crediti

- Basata su [Cosy Home](https://github.com/oliverjwroberts/cosy-home-tv) di Oliver Roberts, a sua volta nata
  da [cleanyourtv](https://github.com/Gathin23/cleanyourtv) di Gathin (MIT, vedi `LICENSE`).
- La lettura degli ultimi contenuti segue il metodo di [SferaDev/android-tv](https://github.com/SferaDev/android-tv) (MIT).
- Carattere [Outfit](https://github.com/Outfitio/Outfit-Fonts), SIL Open Font License (`FONT-LICENSE-OFL.txt`).
- Dati meteo di [Open-Meteo.com](https://open-meteo.com), CC BY 4.0.
