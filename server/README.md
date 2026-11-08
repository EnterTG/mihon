# Biblioteka sieciowa Mihon na TrueNAS Community Edition

Ten wariant Mihon zapisuje pobierane obrazy bezpośrednio na Twoim serwerze. Android przekazuje usłudze listę adresów obrazów i nagłówki źródła; **obrazy pobiera serwer**, a aplikacja odbiera je dopiero do czytania. Wybranie „Pobierz” w aplikacji archiwizuje cały wybrany rozdział na NAS, bez pobierania jego obrazów na telefon.

Na telefonie pozostaje baza biblioteki (tytuły, ulubione, historia i postęp czytania), okładki, rozszerzenia, indeks zawartości serwera oraz tymczasowe obrazy. To przechowywanie treści na serwerze; nie jest to synchronizacja całej bazy aplikacji między urządzeniami. Dotychczasowe lokalne pliki nie są przenoszone ani kasowane.

## Co przygotować na TrueNAS

Utwórz dataset na archiwum, np. `/mnt/tank/manga/mihon`, oraz katalog na kod, np. `/mnt/tank/apps/mihon`. Przyznaj UID/GID **568:568** prawo odczytu, tworzenia i usuwania plików w datasecie archiwum. Kontener działa jako ten użytkownik, bez uprawnień administratora.

Usługa to samodzielny kontener Python bez dodatkowych pakietów. Nie instaluj niczego do systemowego Pythona TrueNAS. Wymagany jest Docker/Apps dostępny w TrueNAS Community Edition. Oficjalna instrukcja instalowania aplikacji niestandardowych: https://apps.truenas.com/managing-apps/installing-custom-apps/.

### Uruchomienie przez Compose

W powłoce TrueNAS, z uprawnieniami do Dockera:

```sh
git clone --branch feature/truenas-remote-library https://github.com/EnterTG/mihon.git /mnt/tank/apps/mihon
cd /mnt/tank/apps/mihon/server
cp .env.example .env
chmod 600 .env
openssl rand -hex 32
```

Edytuj `.env`: wpisz ścieżkę swojego datasetu jako `MIHON_DATA`, a wynik ostatniego polecenia jako `MIHON_TOKEN`. Zachowaj token — ten sam wpiszesz w aplikacji. Nie udostępniaj pliku `.env`.

```sh
docker compose up -d --build
docker compose logs --tail=50
```

Usługa nasłuchuje na porcie **8088** hosta. Test z tokenem odczytanym z `.env`:

```sh
curl -H "Authorization: Bearer TWOJ_TOKEN" http://ADRES_TRUENAS:8088/v1/chapters
```

Początkowo odpowiedź to `[]`. Błąd 401 oznacza niezgodny token. Brak połączenia oznacza zwykle błędny adres, port lub regułę zapory. Błąd zapisu oznacza najczęściej brak praw UID 568 do datasetu.

### Alternatywnie: Apps → Discover Apps → Install via YAML

Najpierw zbuduj obraz z katalogu `server`:

```sh
docker build -t mihon-library:local .
```

W edytorze YAML TrueNAS wklej poniższy Compose i **zastąp token oraz ścieżkę datasetu**:

```yaml
services:
  mihon-library:
    image: mihon-library:local
    pull_policy: never
    user: "568:568"
    ports:
      - "8088:8080"
    environment:
      MIHON_TOKEN: "WSTAW_WLASNY_LOSOWY_TOKEN_MINIMUM_32_ZNAKI"
    volumes:
      - /mnt/tank/manga/mihon:/data
    restart: unless-stopped
    read_only: true
    tmpfs:
      - /tmp
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
```

Wybierz jeden sposób uruchomienia, aby dwa kontenery nie używały jednocześnie tego samego portu i datasetu. Dane leżą w datasecie, więc usunięcie samego kontenera nie usuwa archiwum. Dataset można objąć snapshotami i replikacją TrueNAS. Obraz `mihon-library:local` jest budowany lokalnie; nie jest publikowany w rejestrze.

## Konfiguracja aplikacji

Zainstaluj build Androida zawierający tę zmianę. Standardowa aplikacja Mihon nie ma tej funkcji.

1. Otwórz **Ustawienia → Pobieranie → Biblioteka sieciowa (TrueNAS)**.
2. Wpisz adres `http://ADRES_TRUENAS:8088` albo adres HTTPS reverse proxy, bez dodatkowej ścieżki.
3. Wpisz token z konfiguracji kontenera.
4. Użyj **Sprawdź połączenie i odśwież bibliotekę**, następnie włącz **Zapisuj pobierane rozdziały na serwerze**. Włączenie wymaga udanego połączenia.
5. Dodaj tytuł do biblioteki zwykłym sposobem i wybierz rozdziały do pobrania. Status pobrania i liczniki w tym trybie dotyczą archiwum na NAS.

Po zmianach z innego urządzenia odśwież katalog przyciskiem w ustawieniach. Serwer identyfikuje treść przez identyfikator źródła, adres mangi i adres rozdziału; nazwy plików nie zależą od tytułów ani lokalnych identyfikatorów bazy Androida. Kopiowanie archiwum nie dodaje automatycznie mang do ulubionych na drugim urządzeniu.

## Bufor: poprzedni + bieżący + dwa następne

Przy zmianie aktywnego rozdziału aplikacja wyznacza okno według kolejności czytnika. Wszystkie obrazy spoza tego okna są usuwane z osobnego cache `remote_library`. Zapis obrazu i przesunięcie okna są synchronizowane: spóźnione pobieranie nie może odtworzyć usuniętego rozdziału. Zapisy są atomowe; po nieudanym pobraniu nie zostaje częściowy obraz.

Najpierw buforowany jest bieżący rozdział, potem dwa następne, a następnie poprzedni. Pobieranie z wyprzedzeniem przestrzega ustawienia czytnika „tylko przez Wi-Fi”. Na początku lub końcu listy bufor jest mniejszy. Odczyt wcześniej zbuforowanych obrazów działa bez serwera, o ile Android nie wyczyścił cache. Nie ma automatycznego przełączenia na pobieranie obrazów ze źródła na telefon, gdy NAS jest niedostępny.

Bufor ma limit liczby rozdziałów, nie stałą wielkość w MB. Jeden obraz może mieć maksymalnie 64 MiB, rozdział do 2000 stron i 2 GiB na serwerze. Duży rozdział może wymagać dużo miejsca nawet przy oknie czterech rozdziałów. Bezpośrednie zapisanie/udostępnienie strony przez użytkownika nadal działa jak w Mihon.

Opcje automatycznego usuwania po przeczytaniu i po oznaczeniu jako przeczytane nie kasują archiwum serwera. Ręczne **Usuń pobrane rozdziały** usuwa kopie na NAS (z uwzględnieniem dotychczasowych filtrów kategorii i zakładek). Przesuwanie bufora nie usuwa niczego z NAS. Wyłączenie trybu przywraca obsługę dotychczasowego lokalnego katalogu pobierania.

## Zgodność źródeł i sieć

Rozszerzenia nadal działają na Androidzie: wyszukują tytuły, listy rozdziałów i adresy obrazów. Serwer dostaje rzeczywiste żądanie GET z `imageRequest`, w tym Referer i cookies dla danego adresu. Adresy obrazów, cookies i nagłówki nie są zapisywane w metadanych archiwum. Dostęp do już zarchiwizowanych rozdziałów nie wymaga ponownego ustalania adresów w źródle.

Wersja ta obsługuje standardowe żądania GET do publicznych źródeł HTTP/HTTPS na portach 80/443. Źródła wymagające POST, modyfikowania żądań/odpowiedzi przez interceptory rozszerzenia, odszyfrowywania obrazów na Androidzie albo tokenów związanych z adresem IP mogą nie działać. Ochrona Cloudflare może odrzucić serwer mimo działającego źródła w telefonie. Błąd jest zgłaszany w kolejce; aplikacja nie zastępuje go lokalnym pobraniem.

Nie wystawiaj bezpośrednio nieszyfrowanego portu do Internetu. Dla dostępu zdalnego użyj VPN albo reverse proxy HTTPS z prawidłowym certyfikatem i długim timeoutem dla POST (pobieranie rozdziału może trwać kilka minut). Przy HTTP token i nagłówki źródła są przesyłane otwartym tekstem — używaj go wyłącznie w zaufanej sieci prywatnej. Połączenia do NAS nie podążają za przekierowaniami. Serwer odrzuca pobieranie obrazów z prywatnych, lokalnych i specjalnych adresów IP; sprawdza i przypina adres DNS także po przekierowaniu.

Kolejka zleceń pozostaje w aplikacji. Serwer kończy rozpoczęte zlecenie, ale nie posiada niezależnego harmonogramu aktualizacji mang ani własnego silnika rozszerzeń. Ponowienie zakończonego zlecenia nie pobiera obrazów drugi raz. Niekompletne pobranie jest usuwane, a zlecenie można ponowić.

## Testy

```sh
python3 -m unittest discover -s server/tests -v
./gradlew testDebugUnitTest spotlessCheck
```

Testy serwera używają kontrolowanego źródła obrazów i prawdziwego lokalnego HTTP; nie wymagają dostępu do stron z mangą. Testy Androida obejmują granice okna, usuwanie rozdziałów, spóźniony prefetch i anulowany zapis. Sprawdź również na telefonie: pobranie na NAS bez plików w lokalnym katalogu pobierania, przejście o kilka rozdziałów, cofnięcie, utratę sieci, restart aplikacji oraz ręczne usuwanie z NAS.
