#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# check-exposure.sh
#
# Διαγνωστικό για DSpace 7 / DSpace-CRIS deployment που τρέχει σε Docker.
# Τρέχει στον HOST (τον server που έχει τον Docker daemon) και απαντά:
#   1. Ποιες πόρτες των containers είναι εκτεθειμένες στο internet
#   2. Από πού προέρχεται το εύρημα "Apache Tomcat Default Files"
#   3. Αν λείπει το HSTS και άλλα security headers
#
# Read-only: δεν αλλάζει ΤΙΠΟΤΑ. Ασφαλές να τρέξει σε production.
#
# Χρήση:  sudo ./check-exposure.sh [public-hostname]
#   π.χ.  sudo ./check-exposure.sh gnosis2.lib.ucy.ac.cy
# ---------------------------------------------------------------------------
set -uo pipefail

HOST="${1:-}"
RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[0;33m'; BLU=$'\033[1;34m'; NC=$'\033[0m'

hdr()  { printf '\n%s=== %s ===%s\n' "$BLU" "$1" "$NC"; }
bad()  { printf '  %s[!]%s %s\n' "$RED" "$NC" "$1"; }
ok()   { printf '  %s[ok]%s %s\n' "$GRN" "$NC" "$1"; }
warn() { printf '  %s[?]%s %s\n' "$YLW" "$NC" "$1"; }
info() { printf '      %s\n' "$1"; }

have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
hdr "1. Docker containers και published ports"
# ---------------------------------------------------------------------------
if ! have docker; then
  warn "Το docker CLI δεν βρέθηκε - τρέξε το script στον Docker host."
else
  docker ps --format '  {{.Names}}  ({{.Image}})' 2>/dev/null || \
    warn "Δεν μπορώ να μιλήσω στον docker daemon (δοκίμασε με sudo)."

  printf '\n  Published ports:\n'
  # Κάθε mapping που ΔΕΝ ξεκινάει με 127.0.0.1 είναι ανοιχτό προς τα έξω.
  docker ps --format '{{.Names}}|{{.Ports}}' 2>/dev/null | while IFS='|' read -r name ports; do
    [ -z "${ports:-}" ] && continue
    IFS=',' read -ra maps <<< "$ports"
    for m in "${maps[@]}"; do
      m="$(echo "$m" | xargs)"
      [ -z "$m" ] && continue
      case "$m" in
        *'->'*)
          if [[ "$m" == 127.0.0.1:* || "$m" == '[::1]:'* ]]; then
            ok "$name  $m  (μόνο localhost)"
          else
            bad "$name  $m  <-- ΕΚΤΕΘΕΙΜΕΝΟ ΣΕ ΟΛΑ ΤΑ INTERFACES"
          fi
          ;;
      esac
    done
  done
fi

cat <<'NOTE'

  ΠΡΟΣΟΧΗ - Docker vs firewall:
  Το `docker run -p 8080:8080` / `ports: - "8080:8080"` γράφει κανόνες στις
  DOCKER / DOCKER-USER αλυσίδες του iptables (nat + FORWARD). Το ufw και το
  firewalld διαχειρίζονται την INPUT αλυσίδα. Αποτέλεσμα: ένα
  `ufw deny 8080/tcp` ΔΕΝ μπλοκάρει published Docker port. Η πόρτα μένει
  ανοιχτή. Ο σωστός τρόπος είναι το binding στο compose:
      ports: - "127.0.0.1:8080:8080"
NOTE

# ---------------------------------------------------------------------------
hdr "2. Τι ακούει στον host"
# ---------------------------------------------------------------------------
if have ss; then
  LISTEN="$(ss -lntp 2>/dev/null)"
elif have netstat; then
  LISTEN="$(netstat -lntp 2>/dev/null)"
else
  LISTEN=""
  warn "Ούτε ss ούτε netstat διαθέσιμα."
fi

if [ -n "$LISTEN" ]; then
  # πόρτες που ΔΕΝ πρέπει ποτέ να είναι δημόσιες σε DSpace deployment
  declare -A RISK=(
    [8080]="Tomcat HTTP - πηγή του ευρήματος 'Tomcat Default Files'"
    [8009]="Tomcat AJP - attack class Ghostcat (CVE-2020-1938) αν secretRequired=false"
    [8000]="JDWP java debugger - ΧΩΡΙΣ AUTHENTICATION = remote code execution"
    [5432]="PostgreSQL - η βάση του DSpace"
    [8983]="Solr admin - χωρίς auth by default, διαβάζει/σβήνει cores"
    [4000]="Angular SSR (Node) - πρέπει να περνά μόνο μέσα από τον reverse proxy"
  )
  for p in 8080 8009 8000 5432 8983 4000; do
    line="$(echo "$LISTEN" | awk -v p=":$p\$" '$4 ~ p {print $4}' | head -1)"
    [ -z "$line" ] && continue
    if [[ "$line" == 127.0.0.1:* || "$line" == '[::1]:'* ]]; then
      ok "port $p bound στο localhost  (${RISK[$p]})"
    else
      bad "port $p ακούει στο $line  --  ${RISK[$p]}"
    fi
  done
  printf '\n  Δημόσια (0.0.0.0 / *) listening sockets:\n'
  echo "$LISTEN" | awk 'NR>1 && ($4 ~ /^0\.0\.0\.0:/ || $4 ~ /^\*:/ || $4 ~ /^\[::\]:/) {print "      " $4 "  " $NF}' | sort -u
fi

# ---------------------------------------------------------------------------
hdr "3. Tomcat default files - ΜΕΣΑ στο container"
# ---------------------------------------------------------------------------
# Το official tomcat:9 image μεταφέρει τα default webapps στο webapps.dist/
# και αφήνει το webapps/ άδειο. Άρα ένα καθαρό DSpace container ΔΕΝ σερβίρει
# /docs, /examples, /manager. Αν τα βλέπεις, κάποιος τα επανέφερε.
CT="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -iE '^dspace$|dspace.*(rest|server|backend|tomcat)' | grep -viE 'angular|ui|db|solr' | head -1)"
if [ -z "${CT:-}" ]; then
  warn "Δεν βρήκα αυτόματα το backend container. Τρέξε χειροκίνητα:"
  info "docker exec <container> ls -la /usr/local/tomcat/webapps/ /usr/local/tomcat/webapps.dist/"
else
  info "backend container: $CT"
  printf '\n  /usr/local/tomcat/webapps/ :\n'
  docker exec "$CT" ls -1 /usr/local/tomcat/webapps/ 2>/dev/null | sed 's/^/      /'
  for d in ROOT docs examples manager host-manager; do
    if docker exec "$CT" test -e "/usr/local/tomcat/webapps/$d" 2>/dev/null; then
      bad "υπάρχει /usr/local/tomcat/webapps/$d  -> σερβίρεται στο δίκτυο"
    fi
  done
  if docker exec "$CT" test -d /usr/local/tomcat/webapps.dist 2>/dev/null; then
    ok "τα defaults είναι παρκαρισμένα στο webapps.dist/ (δεν σερβίρονται)"
  fi

  printf '\n  Ρυθμίσεις server.xml που κρύβουν την έκδοση:\n'
  SX="$(docker exec "$CT" cat /usr/local/tomcat/conf/server.xml 2>/dev/null)"
  echo "$SX" | grep -q 'showServerInfo="false"' \
    && ok 'ErrorReportValve showServerInfo="false"' \
    || bad 'ΛΕΙΠΕΙ ErrorReportValve showServerInfo="false" - οι σελίδες 404/500 δείχνουν "Apache Tomcat/9.0.x"'
  echo "$SX" | grep -qE 'protocol="AJP/1\.3"' && {
    if echo "$SX" | grep -qE 'AJP/1\.3.*secretRequired="false"'; then
      bad 'AJP connector ενεργός με secretRequired="false"'
      info 'Αν η 8009 είναι δημόσια -> ανάγνωση αρχείων μέσα στο webapp (web.xml, local.cfg με DB password).'
    else
      warn 'AJP connector ενεργός - βεβαιώσου ότι η 8009 δεν είναι δημόσια'
    fi
  }
  CO="$(docker exec "$CT" printenv CATALINA_OPTS 2>/dev/null || true)"
  case "${CO:-}" in
    *jdwp*) bad "CATALINA_OPTS περιέχει JDWP debugger: $CO"
            info 'Είναι image φτιαγμένο από Dockerfile.test. ΠΟΤΕ σε production.' ;;
    *)      ok "δεν υπάρχει JDWP debugger στο CATALINA_OPTS" ;;
  esac
  if docker exec "$CT" test -e /usr/local/tomcat/webapps/rest 2>/dev/null; then
    bad "deployed το deprecated v6 REST API στο /rest (image από Dockerfile.test)"
  fi
fi

# ---------------------------------------------------------------------------
hdr "4. Tomcat default files - από το ΔΙΚΤΥΟ"
# ---------------------------------------------------------------------------
probe() { # probe <base-url> <label>
  local base="$1" label="$2" p code
  printf '\n  %s\n' "$label"
  for p in "" "docs/" "examples/" "manager/html" "host-manager/html" \
           "RELEASE-NOTES.txt" "LICENSE" "NOTICE" "tomcat.svg" "tomcat.gif" \
           "favicon.ico" "rest/" "server/api"; do
    code="$(curl -sk -o /dev/null -w '%{http_code}' --max-time 8 "$base/$p" 2>/dev/null)"
    case "$code" in
      200|301|302) printf '      %s%-4s%s  %s\n' "$RED" "$code" "$NC" "$base/$p" ;;
      000)         printf '      %s----%s  %s  (δεν απαντά)\n' "$YLW" "$NC" "$base/$p" ;;
      *)           printf '      %s%-4s%s  %s\n' "$GRN" "$code" "$NC" "$base/$p" ;;
    esac
  done
  printf '      %s\n' "Κόκκινο 200/301/302 σε docs|examples|manager|RELEASE-NOTES|tomcat.* = το εύρημα του scanner."
  printf '      %s\n' "Το /server/api ΠΡΕΠΕΙ να είναι 200 - είναι το DSpace REST API."
}
probe "http://127.0.0.1:8080" "Απευθείας στον Tomcat (http://127.0.0.1:8080):"
[ -n "$HOST" ] && probe "https://$HOST" "Δημόσιο HTTPS (https://$HOST):"

printf '\n  Τι δείχνει ο Tomcat ως έκδοση σε μια σελίδα λάθους:\n'
curl -sk --max-time 8 "http://127.0.0.1:8080/this-path-does-not-exist-$$" 2>/dev/null \
  | grep -oiE 'Apache Tomcat/[0-9.]+' | head -1 | sed 's/^/      LEAK: /' \
  || info "(καμία διαρροή έκδοσης, ή ο Tomcat δεν απαντά τοπικά)"

# ---------------------------------------------------------------------------
hdr "5. HSTS και security headers"
# ---------------------------------------------------------------------------
if [ -z "$HOST" ]; then
  warn "Δώσε το public hostname: sudo $0 gnosis2.lib.ucy.ac.cy"
else
  H="$(curl -skI --max-time 15 "https://$HOST/" 2>/dev/null)"
  if [ -z "$H" ]; then
    bad "δεν πήρα απάντηση από https://$HOST/"
  else
    check_hdr() { # check_hdr <header> <required: yes|nice>
      local v; v="$(echo "$H" | grep -i "^$1:" | head -1 | sed 's/\r$//')"
      if [ -n "$v" ]; then ok "$v"
      elif [ "$2" = yes ]; then bad "ΛΕΙΠΕΙ: $1"
      else warn "λείπει (προαιρετικό): $1"; fi
    }
    check_hdr "Strict-Transport-Security" yes
    check_hdr "X-Content-Type-Options"    nice
    check_hdr "Referrer-Policy"           nice
    check_hdr "X-Frame-Options"           nice
    check_hdr "Content-Security-Policy"   nice
    echo "$H" | grep -iE '^(Server|X-Powered-By):' | sed 's/\r$//' \
      | sed "s/^/  ${YLW}[?]${NC} version disclosure: /"
  fi

  printf '\n  HTTP -> HTTPS redirect:\n'
  R="$(curl -sI --max-time 15 "http://$HOST/" 2>/dev/null | sed 's/\r$//')"
  C="$(echo "$R" | head -1)"
  L="$(echo "$R" | grep -i '^location:' | head -1)"
  case "$C" in
    *30[128]*) [[ "$L" == *https://* ]] && ok "$C -> $L" || bad "redirect αλλά όχι σε https: $C $L" ;;
    "")        warn "η πόρτα 80 δεν απαντά (ok αν είναι κλειστή επίτηδες)" ;;
    *)         bad "δεν γίνεται redirect σε HTTPS: $C" ;;
  esac
fi

hdr "Τέλος"
cat <<'NOTE'
  Επόμενα βήματα: security-hardening/README.md
    - κόκκινα στο (1)/(2)  -> docker-compose.prod.yml  (port bindings)
    - κόκκινα στο (3)/(4)  -> Dockerfile.tomcat-hardened + apache/nginx blocks
    - κόκκινα στο (5)      -> apache-dspace-vhost.conf / nginx-dspace.conf (HSTS)
NOTE
