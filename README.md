# Chat2Discord Client (Fabric 1.21.4)

Mod kliencki: instalujesz go u siebie i działa na **każdym serwerze**, bez zgody i bez dostępu do serwera.
Wpisujesz w grze `/dc cokolwiek`, a bot wysyła na Twój kanał Discorda:

**TwójNick** wysłał: cokolwiek

Komenda działa tylko lokalnie i nie jest wysyłana na serwer Minecrafta.

## 1. Webhook na Discordzie
Discord → Ustawienia kanału → Integracje → Webhooki → Nowy webhook → Kopiuj URL.

## 2. Budowanie
Wymagane: Java 21 i Gradle.

```
gradle build
```

Gotowy plik: `build/libs/chat2discord-client-1.0.0.jar`

## 3. Instalacja
1. Zainstaluj Fabric Loader dla 1.21.4 oraz Fabric API.
2. Wrzuć jar do folderu `mods/` w swoim `.minecraft`.
3. Uruchom grę raz – powstanie `config/chat2discord-client.properties`.
4. Wklej URL webhooka w `webhook_url=` i ustaw `commands=` (np. `commands=dc,name`).
5. Zrestartuj grę. Potem `/c2d reload` wczytuje pozostałe ustawienia bez restartu.

## Konfiguracja
| Opcja | Opis |
|---|---|
| `webhook_url` | URL webhooka (nie udostępniaj tego pliku!) |
| `commands` | nazwy komend bez `/`, po przecinku (zmiana wymaga restartu gry) |
| `mode` | wiadomości z czatu: `off`, `filter` (tylko z triggerem), `all` |
| `triggers`, `match`, `strip_trigger` | ustawienia filtra czatu |
| `format` | `{player}` nick, `{message}` treść, `{server}` adres serwera |
| `notify_player` / `notify_text` | potwierdzenie w grze |
| `blocked_words` | słowa/nicki po przecinku — jeśli Twoja wiadomość zawiera którekolwiek z nich, NIE zostanie wysłana |
| `blocked_text` | komunikat, który zobaczysz, gdy wiadomość zostanie zablokowana |

Wysyła tylko to, co sam wpiszesz. Wiadomości innych graczy nie są wysyłane.
Uwaga: część serwerów zabrania modów klienckich, więc sprawdź regulamin.

## Budowanie bez instalowania czegokolwiek (GitHub Actions)
1. Załóż darmowe konto na github.com i utwórz nowe repozytorium (New repository).
2. Kliknij "uploading an existing file" i wgraj całą zawartość tego folderu (razem z folderem `.github`). Nie wgrywaj tam swojego webhooka.
3. Kliknij Commit changes. Wejdź w zakładkę **Actions** i poczekaj na zielony znaczek (kilka minut).
4. Otwórz ukończone uruchomienie, na dole w sekcji **Artifacts** pobierz `mod-jar` i rozpakuj. W środku jest gotowy plik `.jar`.
