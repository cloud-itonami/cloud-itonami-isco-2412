(ns finadvisory.store
  "SSoT for the ISCO-08 2412 independent financial & investment
  advisory practice actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section; README's 'Robotics premise' — a secure
  document-handling and archival robot performs statement printing,
  disclosure packet assembly and physical archival under this
  advisor/governor pair, which never dispatches hardware itself, never
  executes trades and never transfers funds). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client  — a registered organization/individual (:client-id, :name)
    account — a registered advisory account {:account-id :client-id
              :name :max-allocation-pct number}. `:max-allocation-pct`
              is the registered suitability ceiling — the maximum
              percentage of the client's portfolio a single proposed
              recommendation may allocate to one position/asset class,
              set from the client's registered risk-profile/mandate.
              Recommending beyond it is unsuitable advice, not
              aggressive strategy.
    record  — a committed operating record (an issued recommendation)
              — written ONLY via commit-record!.
    ledger  — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (account [s account-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-account! [s a])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (account [_ account-id] (get-in @a [:accounts account-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-account! [s acct]
    (swap! a assoc-in [:accounts (:account-id acct)] acct) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :accounts {} :records [] :ledger []}
                                   seed)))))
