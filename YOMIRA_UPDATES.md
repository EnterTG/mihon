# Podpisywanie i aktualizacje Yomira (Android)

Yomira to oddzielna aplikacja o identyfikatorze `app.yomira`. Aby aktualizacja
instalowała się **na istniejącej aplikacji** bez utraty danych, każde wydanie APK
musi być podpisane **tym samym prywatnym kluczem**.

## Jednorazowa konfiguracja właściciela repozytorium

1. Utwórz osobny keystore PKCS12 dla Yomira (alias `yomira`). Zachowaj plik
   i jego hasło w bezpiecznej kopii zapasowej — utrata klucza oznacza brak
   możliwości wydawania kompatybilnych aktualizacji.

   Przykładowa komenda (Android/JDK `keytool`):

   ```sh
   keytool -genkeypair -v -keystore yomira-release-keystore.p12 -storetype PKCS12 \
     -alias yomira -keyalg RSA -keysize 3072 -validity 10000
   ```

   Zastosuj to samo silne hasło do keystore i klucza. **Nigdy nie commituj**
   pliku ani hasła do publicznego repozytorium.

2. Wejdź w repozytorium GitHub → **Settings → Secrets and variables → Actions →
   New repository secret**. Dodaj:
   - `YOMIRA_KEYSTORE_BASE64`: całą zawartość `base64` keystore (bez białych znaków),
   - `YOMIRA_SIGNING_PASSWORD`: hasło keystore i klucza.

   Przykładowe kodowanie: `base64 -w 0 yomira-release-keystore.p12` (Linux);
   na macOS: `base64 < yomira-release-keystore.p12 | tr -d '\n'`.

3. Uruchom **Actions → Build & Test → Run workflow** na `main`. Po pomyślnych
   testach workflow publikuje podpisany APK uniwersalny i ARM64 na
   [Releases](https://github.com/EnterTG/mihon/releases). Gdy brakuje któregokolwiek
   sekretu, CI uruchamia testowy build, lecz celowo **nie publikuje nowego wydania**
   z nietrwałym podpisem.

Każde następne poprawne wydanie będzie miało rosnący `versionCode` oraz
`versionName` w formacie `0.21.3-yomira-preview.N`.
Gdy zmieniasz podstawowy `versionCode` aplikacji w przyszłości, zapewnij,
by był on większy od wersji z wcześniejszych preview (obecnie
`39_000_000 + N`).

## Zachowanie na telefonie

Yomira przy otwarciu sprawdza wydania GitHub (także *prerelease*),
okresowo ponawia sprawdzanie i wyświetla ekran pobierania nowszego APK.
Po pobraniu użytkownik zatwierdza instalację w Androidzie; system nie zezwala
na bezwarunkową, cichą instalację aktualizacji poza uprawnionymi trybami.

**Wcześniejsze wersje Yomira z GitHub Actions (w tym Preview 49) mogły być
podpisane innym debugowym kluczem.** Android nie pozwala nadpisać ich APK
podpisanym nowym kluczem. Zanim przejdziesz na pierwsze wydanie ze stałym
podpisem, wykonaj kopię danych w Yomira, odinstaluj tylko starą Yomira,
zainstaluj nowe APK i przywróć kopię. Oryginalny Mihon pozostaje osobną
aplikacją i nie musi być odinstalowany.

Weryfikacja CI sprawdza również stały odcisk certyfikatu SHA-256
`0F:4F:78:C2:34:6E:50:88:E2:C3:0D:33:BD:5F:A6:1C:09:8A:03:AC:57:A6:66:D0:30:34:F8:A4:5A:5C:CB:5B`.
Ten odcisk odpowiada przygotowanemu prywatnemu zestawowi startowemu. Jeśli świadomie
wybierzesz inny klucz przed **pierwszym** wydaniem, dostosuj kontrolę
`expected_digest` w workflow. Po pierwszej publikacji nie zmieniaj odcisku ani klucza.

Nie zmieniaj ani nie regeneruj klucza dla kolejnych wydań.

## Kontrola certyfikatu w CI

Podczas publikacji skrypt sprawdza, czy certyfikat keystore i certyfikaty **obu**
wersji APK (uniwersalnej i ARM64) mają dokładnie ten sam odcisk SHA-256.
Narzędzie Android `apksigner` może używać różnych etykiet podpisów
(`Signer #1` lub `Signer (minSdkVersion=...)`), dlatego weryfikator
odczytuje oba warianty. Nie wyłączaj tego sprawdzenia, nawet jeżeli build
APK zakończył się sukcesem.
