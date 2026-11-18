# Hey Claude – Sprachassistent für Android

Sag **„Hey Claude“**, auch wenn die App geschlossen ist. Nach dem Piep stellst du deine Frage, und Claude antwortet laut.

## So funktioniert es
1. **Wake-Word**: Ein Offline-Modell (Vosk) hört ständig auf „Hey Claude“. Dabei verlässt kein Ton das Handy.
2. **Frage**: Nach dem Piep nimmt die Android-Spracherkennung (Google) deine Frage auf.
3. **Antwort**: Die Frage geht an die Claude-API (Standard: `claude-haiku-5-5`, mit Websuche für Wetter, News usw.).
4. **Vorlesen**: Die Antwort wird mit einer echten ElevenLabs-Stimme vorgelesen (oder mit der Handy-Stimme, wenn kein ElevenLabs-Schlüssel eingetragen ist). Stellt Claude eine Rückfrage, hört die App gleich weiter zu.
5. **Handy steuern**: „Nächstes Lied“, „Musik pausieren“, „Mach lauter“, „Lautstärke auf 30 Prozent“, „Handy auf lautlos“, „Taschenlampe an“ oder „Wie viel Akku habe ich?“. Während du sprichst, wird die Musik leiser, und „Hey Claude, stopp“ hält die Musik an.
6. **Standort**: Claude kennt den ungefähren Standort des Handys, zum Beispiel für das Wetter oder für Orte in der Nähe. Das lässt sich in der App ausschalten.

## APK bauen (geht auch nur mit dem Handy)
1. Auf github.com ein **neues Repository** anlegen, zum Beispiel `hey-claude`.
2. **Add file → Upload files** → `HeyClaude-Quellcode.zip` hochladen → *Commit changes*.
3. **Add file → Create new file**. Als Dateinamen genau `.github/workflows/build.yml` eingeben, den Inhalt der Datei `build.yml` einfügen → *Commit changes*.
4. Im Tab **Actions** etwa 5–8 Minuten warten, bis der Build einen grünen Haken hat.
5. Auf der Startseite des Repos rechts unter **Releases** die Datei `HeyClaude.apk` herunterladen und installieren. Dafür musst du „Unbekannte Apps installieren“ erlauben.

Am PC kannst du stattdessen einfach den ganzen Ordnerinhalt hochladen. Der Workflow ist dann schon dabei.

## Einrichten
1. Einen **API-Schlüssel** gibt es auf https://platform.claude.com (API Keys). Dort musst du auch Guthaben aufladen, denn die API wird getrennt vom Claude-Abo bezahlt. Mit Haiku kosten Fragen nur Bruchteile eines Cents, eine Websuche etwa 1 Cent.
2. In der App den Schlüssel eintragen → **Starten** → Mikrofon und Benachrichtigungen erlauben.
3. Für die echte Stimme: auf https://elevenlabs.io/app/settings/api-keys einen Schlüssel erstellen (mit Zugriff auf *Text to Speech* und *Voices*). Den Schlüssel in der App eintragen, dann **Stimme auswählen** → **Stimme testen**. Das Gratis-Kontingent reicht für viele kurze Antworten.
4. **Akku-Optimierung ausschalten** antippen. Das ist vor allem bei Samsung, Xiaomi und Huawei wichtig.
5. Die App schließen und „Hey Claude“ sagen.

## Updates – ohne Installation
Die App besteht aus zwei Teilen:
- **Hülle** (die installierte APK): Berechtigungen, Mikrofon-Dienst, Wake-Word-Modell. Sie ändert sich fast nie.
- **Programmcode** (`core.jar` im Release, ca. 300 KB): die ganze Logik und die Oberfläche.

Neue Versionen sind normalerweise nur neuer Programmcode. Die App holt ihn alle 30 Minuten und beim Öffnen und tauscht ihn **im laufenden Betrieb** aus, also ohne Installation, ohne Neustart und ohne Antippen. Startet eine neue Version nicht sauber, schaltet die App automatisch auf die vorige zurück.

Nur wenn sich die Hülle ändert (neue Berechtigungen, selten), kommt eine APK. Das läuft wie bisher automatisch im WLAN, danach genügt ein Tipp auf den Hinweis.

## Gut zu wissen
- Solange der Assistent läuft, siehst du eine Benachrichtigung (mit den Knöpfen „Fragen“ und „Beenden“) und den grünen Mikrofon-Punkt. Das schreibt Android vor.
- Nach einem **Neustart des Handys** erscheint ein Hinweis. Tippst du darauf, startet der Assistent wieder. Ab Android 14 darf ein Mikrofon-Dienst nicht ganz von selbst starten.
- Das Wake-Word-Modell ist englisch. Am zuverlässigsten klappt es, wenn du „Hey Claude“ englisch aussprichst (etwa „Hey Klod“).
- Mit „Stopp“ oder „Abbrechen“ nach dem Piep brichst du ab.
- Komplett lautlos stellen geht erst, wenn du in der App „Lautlos-Modus erlauben“ antippst. Ohne diese Erlaubnis stellt Claude das Handy nur auf Vibration.
- **Musik & Apps starten:** „Spiel auf YouTube …“, „Spiel auf Spotify …“, „Öffne WhatsApp“. Das klappt direkt, wenn in der App „Apps & YouTube starten erlauben“ an ist, sonst kommt eine Benachrichtigung zum Antippen. Das Handy muss dafür entsperrt sein. Ohne YouTube Premium stoppt YouTube, wenn der Bildschirm ausgeht.
- Anrufe, Timer und Wecker kann Claude noch nicht.
