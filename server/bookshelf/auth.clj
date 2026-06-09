(ns bookshelf.auth
  "Google OIDC sign-in for the bookshelf. The frontend uses Google Identity
  Services (GIS) to obtain an ID token (a signed JWT) in the browser and POSTs it
  here. We verify it by delegating to Google's tokeninfo endpoint over HTTPS
  (cljw.http.client — real TLS, D-257), which checks the RS256 signature + expiry
  for us; we additionally check the audience matches our client id. No passwords,
  no local credential storage — identity is the Google `sub`.

  Local-JWKS verification (fetch https://www.googleapis.com/oauth2/v3/certs and
  verify RS256 ourselves, avoiding the per-login tokeninfo round-trip) is a future
  refinement; tokeninfo is Google-authoritative and simplest for the demo."
  (:require [cljw.http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private tokeninfo-url "https://oauth2.googleapis.com/tokeninfo?id_token=")

(defn client-id
  "The Google OAuth client id, read from the GOOGLE_CLIENT_ID environment
  variable — set locally via direnv (.envrc; see .envrc.example) and on fly.io
  via `fly secrets set GOOGLE_CLIENT_ID=…`. Empty string when unset, which
  disables sign-in with a clear message. Read each call so a value set after
  startup is picked up."
  []
  (or (System/getenv "GOOGLE_CLIENT_ID") ""))

(defn verify
  "Verify a Google ID token. Returns {:sub :email :name :picture} on success,
  or {:error \"...\"} on failure. Google validates the signature + expiry via
  tokeninfo; we check `aud` against our configured client id."
  [id-token]
  (let [cid (client-id)]
    (cond
      (str/blank? cid)      {:error "GOOGLE_CLIENT_ID not configured on the server"}
      (str/blank? id-token) {:error "missing id token"}
      :else
      (try
        (let [r (http/get (str tokeninfo-url id-token))]
          (if-not (= 200 (:status r))
            {:error "Google rejected the token"}
            (let [claims (json/read-str (:body r))
                  aud    (get claims "aud")
                  iss    (get claims "iss")]
              (cond
                (not= aud cid) {:error "token audience mismatch"}
                (not (contains? #{"accounts.google.com" "https://accounts.google.com"} iss))
                {:error "unexpected issuer"}
                :else {:sub     (get claims "sub")
                       :email   (get claims "email")
                       :name    (or (get claims "name") (get claims "email"))
                       :picture (get claims "picture")}))))
        (catch Throwable e {:error (str "verification failed: " e)})))))
