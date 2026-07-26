(ns toysmfg.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (toysmfg.operation -> toysmfg.governor ->
  toysmfg.store). No invented numbers, no timestamps, byte-identical
  across reruns."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [toysmfg.store :as store]
            [toysmfg.operation :as op]
            [toysmfg.phase :as phase]
            [toysmfg.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private operator {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real ToysGamesOperationActor StateGraph through a scenario
  built directly from `toysmfg.store/sample-data!`'s real seed data
  (this repo's own `toysmfg.sim` was run first and checked -- its ids/
  ops match the real seeded batches/equipment and the real governor
  rules, so this mirrors that same verified scenario rather than
  inventing a new one):

    1. `:log-production-batch` batch-001 -- a clean patch, phase-3
       auto-commit (`toysmfg.phase`'s only auto-eligible op).
    2. `:schedule-maintenance` mnt-1 on molding-001 (verified,
       registered molding unit) -- write-enabled at phase 3 but not
       auto-eligible (`:schedule-maintenance` is permanently excluded
       from every phase's `:auto` set) -> escalates -> human plant
       supervisor `approve!` -> commit.
    3. `:flag-safety-concern` concern-1 on molding-001 -- the one op
       whose advisor-assigned `:stake` (`:coordination/safety-concern`)
       is always in `toysmfg.governor/high-stakes` -- ALWAYS escalates
       regardless of confidence or phase -> human `approve!` -> commit.
    4. `:coordinate-shipment` ship-1 on batch-001 (verified, registered,
       well within its recorded quantity) -- escalates on the phase gate
       -> human shipping-approver `approve!` -> commit.
    5. `:schedule-maintenance` mnt-2 on assembly-002 -- assembly-002 is
       seeded `:verified? false :registered? false` -- HARD hold, rule
       `:equipment-not-verified`, never reaches a human.
    6. `:coordinate-shipment` ship-2 on batch-003 -- batch-003 is seeded
       `:verified? false :registered? false` -- HARD hold, rule
       `:batch-not-verified`, never reaches a human.
    7. `:coordinate-shipment` ship-3 on batch-002 -- batch-002 is seeded
       with `:quantity-units 80.0` and `:shipped-units 75.0`; 10 more
       units would exceed it -- HARD hold, rule
       `:shipment-quantity-exceeded`, never reaches a human.
    8. `:schedule-maintenance` mnt-3 on molding-001 with
       `:actuate-equipment? true` -- HARD hold, rule
       `:actuate-equipment-blocked`, PERMANENT (no phase or approval can
       ever override it), never reaches a human.

  Returns the seeded `db` (a `toysmfg.store/MemStore`) after the run, so
  `render` can read every value straight off it."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:product-type :plastic-toy :last-assessed "2026-07-14"}})

    (exec! actor "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "molding-001" :maintenance-type :injection-mold-inspection
                                :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                        :value {:equipment-id "molding-001" :severity :moderate
                                :description "small-parts choking-hazard flagged on last QC sample"}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                        :value {:batch-id "batch-001" :units 50.0 :destination "buyer-retailer-north"}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "assembly-002" :maintenance-type :fastener-torque-check
                                :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t6" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :units 20.0 :destination "buyer-retailer-south"}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :units 10.0 :destination "buyer-retailer-east"}})

    (exec! actor "t8" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                        :value {:equipment-id "molding-001" :maintenance-type :force-run
                                :scheduled-date "2026-09-01" :actuate-equipment? true}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `subject-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:subject` (see `toysmfg.operation/commit-fact` and
  `toysmfg.governor/hold-fact`)."
  [ledger subject-id]
  (last (filter #(= subject-id (:subject %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a subject -- the
  same cond pattern used fleet-wide."
  [fact]
  (cond
    (nil? fact)                        ["muted" "no activity"]
    (= :committed (:t fact))           ["ok" "committed"]
    (= :approval-granted (:t fact))    ["ok" "approved & committed"]
    (= :governor-hold (:t fact))       ["critical" (str "HARD hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))   ["err" "approval-rejected"]
    (= :approval-requested (:t fact))  ["warn" "awaiting approval"]
    :else                              ["muted" "in progress"]))

(defn- batches-table [db]
  (let [ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>product-type</th><th>lot-number</th><th>safety-test-pass-%</th>"
     "<th>quantity (units)</th><th>weight (g)</th><th>defect-rate-%</th>"
     "<th>verified?</th><th>registered?</th><th>shipped (units)</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [b (store/all-batches db)
            :let [fact (last-fact-for ledger (:id b))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id b)) "</code></td>"
             "<td>" (esc (name (:product-type b))) "</td>"
             "<td><code>" (esc (:lot-number b)) "</code></td>"
             "<td>" (esc (:safety-test-pass-percent b)) "</td>"
             "<td>" (esc (:quantity-units b)) "</td>"
             "<td>" (esc (:weight-grams b)) "</td>"
             "<td>" (esc (:defect-rate-percent b)) "</td>"
             "<td>" (if (:verified? b) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (:registered? b) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (esc (:shipped-units b)) "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- equipment-table [db]
  (let [ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>kind</th><th>verified?</th><th>registered?</th>"
     "<th>last-maintenance-date</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [eq (store/all-equipment db)
            :let [fact (last-fact-for ledger (:id eq))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id eq)) "</code></td>"
             "<td>" (esc (name (:kind eq))) "</td>"
             "<td>" (if (:verified? eq) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (:registered? eq) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (:last-maintenance-date eq) (esc (:last-maintenance-date eq)) "&mdash;") "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- draft-records-table
  "The committed maintenance-schedule / shipment-coordination DRAFT
  records this run actually produced via `toysmfg.registry` (through
  `toysmfg.store/commit-record!`), read straight off
  `maintenance-history` / `shipment-history` -- never hand-typed."
  [db]
  (let [maint (store/maintenance-history db)
        ship (store/shipment-history db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>record_id</th><th>kind</th><th>maintenance_id / shipment_id</th><th>equipment_id</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [r (concat maint ship)]
        (str "<tr>"
             "<td><code>" (esc (get r "record_id")) "</code></td>"
             "<td>" (esc (get r "kind")) "</td>"
             "<td><code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code></td>"
             "<td>" (if-let [eid (get r "equipment_id")] (esc eid) "&mdash;") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- always-high-stakes?
  "`toysmfg.advisor`'s `flag-safety-concern` always assigns `:stake
  :coordination/safety-concern` (never any other op does); that is
  exactly `toysmfg.governor/high-stakes`' one member -- so
  `:flag-safety-concern` is the single op that always escalates via the
  confidence/high-stakes gate, regardless of phase or confidence."
  [op]
  (contains? governor/high-stakes
             (case op :flag-safety-concern :coordination/safety-concern nil)))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `toysmfg.phase/phases` (phase 3, this actor's `default-phase`) and
  `toysmfg.governor/high-stakes` -- not invented, just rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th>"
     "<th>auto-eligible?</th><th>always high-stakes (human required)?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort phase/write-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (always-high-stakes? op) "<span class=\"critical\">yes</span>" "no") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>subject</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:subject f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "critical" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>toysmfg.render-html -- Toys &amp; Games Plant Operations Governor operator console</title>\n"
   "<style>"
   (jp-go-dds.skin/dds+skin)
   "</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Toys &amp; Games Plant Operations Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 3240 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Production batches</h2>\n" (batches-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Molding / assembly equipment</h2>\n" (equipment-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Committed records (maintenance-schedule / shipment-coordination drafts)</h2>\n" (draft-records-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (toysmfg.phase &middot; toysmfg.governor/high-stakes)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
