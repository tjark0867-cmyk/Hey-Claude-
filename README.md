# Hey Claude – Sprachassistent für Android

Sag **„Hey Claude“**, auch wenn die App geschlossen ist. Nach dem Piep stellst du deine Frage, und Claude antwortet laut.

## So funktioniert es
1. **Wake-Word**: Ein Offline-Modell (Vosk) hört ständig auf „Hey Claude“. Dabei verlässt kein Ton das Handy.
2. **Frage**: Nach dem Piep nimmt die Android-Spracherkennung (Google) deine Frage auf.
3. **Antwort**: Die Frage geht an die Claude-API (Standard: `claude-haiku-5-5`, mit Websuche für Wetter, News usw.).
4. **Vorlesen**: Die Antwort wird mit der Sprachausgabe des Handys vorgelesen. Stellt Claude eine Rückfrage, hört die App gleich weiter zu.

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
3. **Akku-Optimierung ausschalten** antippen. Das ist vor allem bei Samsung, Xiaomi und Huawei wichtig.
4. Die App schließen und „Hey Claude“ sagen.

## Gut zu wissen
- Solange der Assistent läuft, siehst du eine Benachrichtigung (mit den Knöpfen „Fragen“ und „Beenden“) und den grünen Mikrofon-Punkt. Das schreibt Android vor.
- Nach einem **Neustart des Handys** musst du die App einmal öffnen und „Starten“ tippen. Ab Android 14 darf ein Mikrofon-Dienst nicht von selbst starten.
- Das Wake-Word-Modell ist englisch. Am zuverlässigsten klappt es, wenn du „Hey Claude“ englisch aussprichst (etwa „Hey Klod“).
- Mit „Stopp“ oder „Abbrechen“ nach dem Piep brichst du ab.
- Claude kann am Handy selbst nichts steuern, also keine Timer, Anrufe oder Musik. Die App beantwortet nur Fragen.
