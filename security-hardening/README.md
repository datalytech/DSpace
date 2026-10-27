# Security hardening για DSpace-CRIS σε Docker

Φτιάχτηκε ως απάντηση σε δύο ευρήματα vulnerability scan για το
`gnosis2.lib.ucy.ac.cy` (194.42.36.7):

| Εύρημα | CVSS | Αρχείο που το διορθώνει |
|---|---|---|
| Apache Tomcat Default Files | 5.0 Medium | `Dockerfile.tomcat-hardened` + blocks στο vhost + `docker-compose.prod.yml` |
| HSTS Missing From HTTPS Server (RFC 6797) | 5.8 Medium | `apache-dspace-vhost.conf` ή `nginx-dspace.conf` |

**Κανένα από τα δύο δεν είναι bug του DSpace.** Είναι ρυθμίσεις του Tomcat,
του Docker και του reverse proxy.

---

## Ξεκίνα από εδώ

```bash
# στον Docker host
sudo ./security-hardening/check-exposure.sh gnosis2.lib.ucy.ac.cy
```

Read-only, δεν αλλάζει τίποτα. Λέει ποιες πόρτες είναι εκτεθειμένες, ποια
default Tomcat paths απαντούν πραγματικά και ποια security headers λείπουν.

---

## Εύρημα 1: Apache Tomcat Default Files

### Τι σημαίνει
Ο scanner βρήκε προσβάσιμα τα αρχεία που έρχονται με την εγκατάσταση του
Tomcat: `/docs/` (δείχνει **ακριβή έκδοση**), `/examples/` (sample servlets με
γνωστά XSS/session issues), `/manager/html` (admin UI, στόχος brute force),
την default σελίδα του `ROOT`, `RELEASE-NOTES.txt`, `tomcat.svg`.

Το ρίσκο είναι κυρίως **information disclosure**: ο επιτιθέμενος μαθαίνει την
exact έκδοση και διαλέγει στοχευμένα exploits.

### Πού είναι το μπέρδεμα στην περίπτωσή σου

Το DSpace image βασίζεται στο επίσημο `tomcat:9-jdk11`. Το επίσημο image
κάνει `mv webapps webapps.dist && mkdir webapps`, δηλαδή **αφήνει το
`webapps/` άδειο** και παρκάρει τα defaults στο `webapps.dist/`. Το DSpace
Dockerfile μετά βάζει μέσα μόνο ένα symlink:

```dockerfile
RUN ln -s $DSPACE_INSTALL/webapps/server /usr/local/tomcat/webapps/server
```

Άρα **ένα καθαρό DSpace container δεν σερβίρει `/docs`, `/examples` ή
`/manager`.** Αυτό σημαίνει ότι το εύρημα προέρχεται από αλλού. Οι
πιθανότητες, με σειρά:

1. **Η σελίδα λάθους του Tomcat.** Με άδειο `webapps/`, το `GET /` στην 8080
   γυρνά 404 — αλλά η default `ErrorReportValve` βάζει μέσα στο HTML
   `Apache Tomcat/9.0.xx`. Αρκετοί scanners το μετρούν ως version/default
   file disclosure. Fix: `showServerInfo="false"` (στο
   `Dockerfile.tomcat-hardened`).
2. **Άλλος Tomcat στην ίδια IP.** Η 194.42.36.7 μπορεί να φιλοξενεί και άλλη
   υπηρεσία — παλιό DSpace 5/6 με system Tomcat, Shibboleth IdP, κάποιο άλλο
   webapp. Το `check-exposure.sh` στο βήμα 2 δείχνει τι ακούει στον host.
3. **Κάποιος επανέφερε τα defaults** — ένα `cp -r webapps.dist/* webapps/`
   για να πάρει το manager app, ή custom image. Βήμα 3 του script.
4. **Το `/rest` webapp** από το `Dockerfile.test` (δες πιο κάτω).

Το script δίνει την απάντηση. Μην κάνεις αλλαγές πριν ξέρεις ποιο από τα
τέσσερα είναι.

### Fix

```bash
# 1. Χτίσε το backend από το ΠΑΡΑΓΩΓΙΚΟ Dockerfile (όχι Dockerfile.test)
docker build -t dspace-cris:local -f Dockerfile .

# 2. Πρόσθεσε το hardening layer
docker build -t dspace-cris:hardened \
      --build-arg BASE_IMAGE=dspace-cris:local \
      -f security-hardening/Dockerfile.tomcat-hardened .

# 3. Μπλόκαρε και στον reverse proxy (τα blocks είναι μέσα στα
#    apache-dspace-vhost.conf / nginx-dspace.conf)
```

---

## Εύρημα 2: HSTS Missing (RFC 6797)

### Τι σημαίνει
Το HTTPS δουλεύει, αλλά οι απαντήσεις δεν έχουν header
`Strict-Transport-Security`. Χωρίς αυτό, ο browser δεν έχει οδηγία «για αυτό
το domain μίλα πάντα HTTPS». Αν κάποιος γράψει `gnosis2.lib.ucy.ac.cy` χωρίς
`https://`, το **πρώτο request φεύγει σε plain HTTP** και επιτιθέμενος στο
ίδιο δίκτυο (π.χ. πανεπιστημιακό WiFi) μπορεί να κάνει SSL stripping και να
κλέψει session cookie ή credentials.

**Ένα 301 redirect δεν αρκεί** — το κλεμμένο request έχει ήδη γίνει. Για
DSpace μετράει, γιατί οι admins κάνουν login με username/password στο UI.

### Fix

Μία γραμμή στον reverse proxy:

```apache
# Apache - θέλει: a2enmod headers && systemctl restart apache2
Header always set Strict-Transport-Security "max-age=31536000; includeSubDomains"
```
```nginx
# nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

Πλήρη έτοιμα configs: `apache-dspace-vhost.conf`, `nginx-dspace.conf`.

### Δύο προφυλάξεις

1. **Ξεκίνα με `max-age=300`** για μία-δύο μέρες. Το HSTS είναι
   μη-αναστρέψιμο για όσο διαρκεί το `max-age` στον browser του κάθε χρήστη —
   δεν μπορείς να το πάρεις πίσω. Επιβεβαίωσε ότι δεν σπάει τίποτα
   (OAI-PMH harvesters, SWORD clients, Shibboleth, IIIF viewers, handle
   resolver) και μετά ανέβασέ το στο `31536000`.
2. Το `includeSubDomains` εδώ αφορά **μόνο** το `gnosis2.lib.ucy.ac.cy` και
   subdomains *του*. Δεν επηρεάζει αδέλφια όπως `www.lib.ucy.ac.cy`. Αν ποτέ
   μπει στο `lib.ucy.ac.cy`, τότε ναι — θέλει συνεννόηση με το δίκτυο.
3. Το `preload` **μην** το βάλεις ακόμα: θέλει υποβολή στη λίστα του Chrome
   και είναι πολύ δύσκολο να αναιρεθεί.

### Σχετικό για το DSpace
Μετά το HSTS, βεβαιώσου ότι δεν υπάρχει mixed content:
- `dspace.server.url = https://gnosis2.lib.ucy.ac.cy/server`
- `dspace.ui.url = https://gnosis2.lib.ucy.ac.cy`
- Angular: `DSPACE_REST_SSL=true`, `DSPACE_REST_PORT=443`
- Στον proxy: `X-Forwarded-Proto: https` (αλλιώς το DSpace παράγει http URLs)

Όλα ρυθμισμένα στο `docker-compose.prod.yml`.

---

## Αυτά που δεν βρήκε ο scanner αλλά βρήκα στο repo

Το `docker-compose.yml` στη ρίζα χτίζει από **`Dockerfile.test`**, που λέει
μόνο του *«meant for TESTING/DEVELOPMENT ONLY»*, και δημοσιεύει πόρτες σε
**όλα τα interfaces**. Αν αυτό τρέχει σε production, τα παρακάτω είναι πολύ
σοβαρότερα από δύο mediums:

| Τι | Πού | Γιατί |
|---|---|---|
| **JDWP debugger** στην 8000 | `Dockerfile.test`: `CATALINA_OPTS=-Xrunjdwp:...,address=*:8000` | Το JDWP **δεν έχει authentication**. Όποιος συνδεθεί εκτελεί αυθαίρετο κώδικα στο JVM. Είναι RCE, όχι medium. |
| **AJP στο 0.0.0.0:8009** με `secretRequired="false"` | `Dockerfile.test` | Το ίδιο το σχόλιο λέει ότι επιτρέπεται μόνο όταν το AJP δεν είναι προσβάσιμο έξω. Εκτεθειμένο ανοίγει την οικογένεια Ghostcat (CVE-2020-1938): ανάγνωση `WEB-INF/web.xml` και configs με DB passwords. |
| **v6 REST API στο `/rest`** με web.xml που δέχεται plain HTTP | `Dockerfile.test` | Το σχόλιο: *«WARNING: THIS IS OBVIOUSLY INSECURE. NEVER DO THIS IN PRODUCTION.»* |
| **PostgreSQL 5432 δημόσια**, password `dspace` | `docker-compose.yml` | Η βάση του repository. |
| **Solr 8983 δημόσια** | `docker-compose.yml` | Χωρίς authentication by default. Ιστορικά config-set upload → RCE. |

### Και η παγίδα με το firewall

```
ports:
  - "8080:8080"      # <-- δημόσιο, ΚΑΙ με ufw enabled
  - "127.0.0.1:8080:8080"   # <-- σωστό
```

Το Docker γράφει κανόνες στις `DOCKER` / `DOCKER-USER` αλυσίδες (`nat` +
`FORWARD`). Το ufw και το firewalld διαχειρίζονται την `INPUT`. **Ένα
`ufw deny 8080/tcp` δεν μπλοκάρει published Docker port** — η πόρτα μένει
ανοιχτή και το `ufw status` δείχνει ότι όλα είναι καλά. Ο σωστός τρόπος είναι
το binding στο αριστερό μέρος του mapping, όπως στο
`docker-compose.prod.yml`.

Έλεγχος:
```bash
sudo iptables -t nat -L DOCKER -n | grep -E '8080|8009|8000|5432|8983'
curl -s -o /dev/null -w '%{http_code}\n' http://194.42.36.7:8080/
```

---

## Περιεχόμενα

| Αρχείο | Τι κάνει |
|---|---|
| `check-exposure.sh` | Read-only διαγνωστικό. **Τρέξε αυτό πρώτα.** |
| `Dockerfile.tomcat-hardened` | Thin layer: σβήνει default webapps, κρύβει version, πετά AJP και JDWP, προσθέτει `RemoteIpValve` |
| `apache-dspace-vhost.conf` | Πλήρες Apache vhost: HSTS, security headers, blocks, proxy |
| `nginx-dspace.conf` | Το ίδιο για nginx (διάλεξε ένα από τα δύο) |
| `docker-compose.prod.yml` | Πρότυπο production compose: localhost-only ports, παραγωγικό Dockerfile, https URLs |
| `.env.prod.example` | Τα env vars που θέλει το compose |

---

## Σειρά εφαρμογής

1. `check-exposure.sh` — μάθε τι ισχύει πραγματικά
2. **HSTS** στον proxy με `max-age=300`, επιβεβαίωση, μετά `31536000`
   *(5 λεπτά, χωρίς downtime)*
3. **Port bindings** σε `127.0.0.1` και σβήσιμο των 8000/8009/5432/8983
   *(ένα `docker compose up -d`, λίγα δευτερόλεπτα downtime)*
4. **Rebuild** από `Dockerfile` + `Dockerfile.tomcat-hardened`
   *(θέλει maven build, κάνε το σε staging πρώτα)*
5. Ξανατρέξε `check-exposure.sh` και ζήτα re-scan

Τα βήματα 2 και 3 κλείνουν και τα δύο ευρήματα του scanner. Το 4 είναι το
σωστό fix στη ρίζα.

## Επαλήθευση

```bash
# HSTS
curl -sI https://gnosis2.lib.ucy.ac.cy/ | grep -i strict-transport

# Tomcat default files - θέλουμε 404/403 σε όλα
for p in docs/ examples/ manager/html RELEASE-NOTES.txt tomcat.svg rest/; do
  printf '%-20s %s\n' "$p" \
    "$(curl -s -o /dev/null -w '%{http_code}' https://gnosis2.lib.ucy.ac.cy/$p)"
done

# Το REST API πρέπει να δουλεύει κανονικά
curl -s https://gnosis2.lib.ucy.ac.cy/server/api | head -c 200

# Καμία πόρτα εκτός 80/443 από έξω
nmap -Pn -p 80,443,4000,5432,8000,8009,8080,8983 194.42.36.7
```
