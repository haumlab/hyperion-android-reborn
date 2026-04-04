#!/bin/bash
FILES=$(find . -name "strings.xml" | grep -v "values/strings.xml")

for FILE in $FILES; do
  # Check if we already added it
  if grep -q "pref_title_wled_enabled" "$FILE"; then
    continue
  fi

  # Determine locale
  LOCALE=$(basename $(dirname "$FILE"))
  
  # Default to english for undefined translations
  ENABLED="Enable WLED DDP"
  ENABLED_SUM="Send pixel data directly to a WLED instance using DDP"
  IP="WLED IP Address"
  IP_SUM="The IP address of your WLED instance"
  
  case "$LOCALE" in
    *es*)
      ENABLED="Habilitar WLED DDP"
      ENABLED_SUM="Enviar datos de píxeles directamente a una instancia WLED usando DDP"
      IP="Dirección IP de WLED"
      IP_SUM="La dirección IP de tu instancia WLED"
      ;;
    *de*)
      ENABLED="WLED DDP aktivieren"
      ENABLED_SUM="Pixeldaten direkt über DDP an eine WLED-Instanz senden"
      IP="WLED-IP-Adresse"
      IP_SUM="Die IP-Adresse deiner WLED-Instanz"
      ;;
    *fr*)
      ENABLED="Activer WLED DDP"
      ENABLED_SUM="Envoyer des données de pixels directement à une instance WLED via DDP"
      IP="Adresse IP WLED"
      IP_SUM="L'adresse IP de votre instance WLED"
      ;;
    *it*)
      ENABLED="Abilita WLED DDP"
      ENABLED_SUM="Invia i dati dei pixel direttamente a un'istanza WLED tramite DDP"
      IP="Indirizzo IP WLED"
      IP_SUM="L'indirizzo IP della tua istanza WLED"
      ;;
    *ru*)
      ENABLED="Включить WLED DDP"
      ENABLED_SUM="Отправка пиксельных данных напрямую на экземпляр WLED через DDP"
      IP="IP-адрес WLED"
      IP_SUM="IP-адрес вашего экземпляра WLED"
      ;;
    *nl*)
      ENABLED="WLED DDP inschakelen"
      ENABLED_SUM="Stuur pixeldata rechtstreeks naar een WLED instantie via DDP"
      IP="WLED IP-adres"
      IP_SUM="Het IP-adres van je WLED instantie"
      ;;
    *cs*)
      ENABLED="Povolit WLED DDP"
      ENABLED_SUM="Odeslat data pixelů přímo do instance WLED přes DDP"
      IP="IP adresa WLED"
      IP_SUM="IP adresa vaší instance WLED"
      ;;
    *no*)
      ENABLED="Aktiver WLED DDP"
      ENABLED_SUM="Send pikseldata direkte til en WLED-instans ved bruk av DDP"
      IP="WLED IP-adresse"
      IP_SUM="IP-adressen til WLED-instansen din"
      ;;
  esac
  
  # Sed to insert before </resources>
  # GNU sed vs BSD sed on mac:
  sed -i '' -e '$ d' "$FILE"
  echo '  <string name="pref_title_wled_enabled">'"$ENABLED"'</string>' >> "$FILE"
  echo '  <string name="pref_summary_wled_enabled">'"$ENABLED_SUM"'</string>' >> "$FILE"
  echo '  <string name="pref_title_wled_ip">'"$IP"'</string>' >> "$FILE"
  echo '  <string name="pref_summary_wled_ip">'"$IP_SUM"'</string>' >> "$FILE"
  echo '</resources>' >> "$FILE"
done
