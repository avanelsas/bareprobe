(ns bareprobe.core-test
  "Tests for the verbs in `bareprobe.core`. The fixture log is produced by stepping real resources
  through `barebuild.resource/step`, so the records hold what the recorder would have held."
  (:require [barebuild.resource :as resource]
            [bareprobe.core :as core]
            [cljs.test :refer [deftest is testing]]
            [clojure.core.protocols :as p]))

;; The fixture log ------------------------------------------------------------------------

(defn- play
  "Records for `rid`, produced by stepping `r0` through `steps`. A step is an event, or a function
  of the current resource returning an event."
  [rid r0 steps]
  (loop [r r0, [s & more] steps, out []]
    (if (nil? s)
      out
      (let [event                      (if (fn? s) (s r) s)
            {:keys [resource effects]} (resource/step r event)]
        (recur resource more
               (conj out {:resource/id rid :el nil :event event
                          :before r :after resource :effects effects}))))))

(defn- read-id
  "The id of the read `r` has in flight."
  [r]
  (:request/id (:active-request r)))

(def ^:private accepted
  {:outcome   :accepted
   :revision  "tasks:v1"
   :query     {}
   :page-info {:page 1 :page-size 10 :total-pages 1 :total-count 1}
   :shape     {:id-key "id" :fields [{:key "title" :type :string :required true}]}
   :value     [{"id" "1" "title" "One"}]})

(def ^:private tasks-0
  (resource/initial {:resource/id "tasks" :endpoint "/api/tasks" :url-intent {}}))

(def ^:private projects-0
  (resource/initial {:resource/id "projects" :endpoint "/api/projects" :url-intent {}}))

(def ^:private tasks-records
  (play "tasks" tasks-0
        [[:connected {:embed nil}]
         (fn [r] [:response (assoc accepted :request/id (read-id r))])
         [:some-future-event {}]
         [:intent-patch {:query-patch {:search "x"} :gesture-class :refinement}]]))

(def ^:private projects-records
  (play "projects" projects-0 [[:connected {:embed nil}]]))

;; Interleaved, so state has to select by resource id. Positions are 1-based:
;; 1 tasks connected, 2 projects connected, 3 tasks response, 4 tasks unknown, 5 tasks intent.
(def ^:private entries
  (vec (concat [(nth tasks-records 0)]
               projects-records
               (rest tasks-records))))

(defn- after [n] (:after (nth entries (dec n))))

(deftest fixture-is-what-the-tests-assume
  (testing "the response installed rather than failing the contract check"
    (is (nil? (:last-failure (after 3))))
    (is (some? (:last-accepted (after 3)))))
  (testing "the unknown event moved nothing"
    (is (= (after 3) (after 4)))))

;; state ----------------------------------------------------------------------------------

(deftest state-at-zero-is-the-seed-of-every-resource
  (let [{:keys [probe/position probe/resources]} (core/state entries 0)]
    (is (= 0 position))
    (is (= {"tasks" tasks-0 "projects" projects-0} resources))))

(deftest state-tracks-each-resource-through-its-own-records
  (testing "a resource with no record yet stays at its seed"
    (is (= projects-0 (get (:probe/resources (core/state entries 1)) "projects"))))
  (testing "each position matches the record's own after"
    (doseq [n [1 2 3 4 5]]
      (let [rid       (:resource/id (nth entries (dec n)))
            resources (:probe/resources (core/state entries n))]
        (is (= (after n) (get resources rid)) (str "position " n)))))
  (testing "the arity without a position covers the whole log"
    (is (= (:probe/resources (core/state entries (count entries)))
           (:probe/resources (core/state entries))))))

(deftest state-reports-a-step-that-changed-nothing
  (testing "a clean prefix reports none"
    (is (= [] (:probe/diagnostics (core/state entries 3)))))
  (testing "the unknown event at position 4 is reported, with its position"
    (is (= [{:probe/code     :unknown-event
             :probe/detail   {:event :some-future-event}
             :probe/position 4}]
           (:probe/diagnostics (core/state entries 5))))))

(deftest state-refuses-a-position-that-does-not-exist
  (testing "past the end"
    (is (thrown? js/Error (core/state entries 999))))
  ;; The ignores are the point of the tests: these are the values a static check would reject and
  ;; a REPL caller can still pass.
  (testing "before the start"
    #_{:clj-kondo/ignore [:type-mismatch]}
    (is (thrown? js/Error (core/state entries -1))))
  (testing "not a position at all"
    #_{:clj-kondo/ignore [:type-mismatch]}
    (is (thrown? js/Error (core/state entries "3"))))
  (testing "the ends of the range are positions"
    (is (some? (core/state entries 0)))
    (is (some? (core/state entries (count entries))))))

(deftest a-refusal-carries-the-one-diagnostic-shape
  (testing "a code and a detail, the same shape a step reports"
    (let [{:keys [probe/code probe/detail]}
          (try (core/at entries 999) (catch :default e (ex-data e)))]
      (is (= :no-such-position code))
      (is (= {:position 999 :lowest 1 :highest 5} detail))))
  (testing "and the message says what would have worked"
    (is (re-find #"answers 1 through 5"
                 (try (core/at entries 999) (catch :default e (ex-message e))))))
  (testing "an empty log says it answers none rather than naming an empty range"
    (is (re-find #"answers none"
                 (try (core/at [] 1) (catch :default e (ex-message e)))))
    (testing "though position 0 of an empty log is a fold position and is not refused"
      (is (some? (core/state [] 0))))))

(deftest at-and-diff-refuse-position-zero
  (testing "no record precedes the first event"
    (is (thrown? js/Error (core/at entries 0)))
    (is (thrown? js/Error (core/diff entries 0)))))

(deftest resource-is-the-way-into-one-of-them
  (testing "at a position, and at the end by default"
    (is (= (after 3) (core/resource entries "tasks" 3)))
    (is (= (get (:probe/resources (core/state entries)) "tasks")
           (core/resource entries "tasks"))))
  (testing "at its seed before it has a record"
    (is (= projects-0 (core/resource entries "projects" 1))))
  (testing "a resource the log never mentions"
    (is (nil? (core/resource entries "nope")))))

(defn- label-of
  "The label `summary` gives `event`."
  [event]
  (:probe/label (first (core/summary [{:resource/id "tasks" :event event
                                       :before {} :after {} :effects []}]))))

(deftest a-label-phrases-the-event
  (testing "the two BareReplay left as their own key"
    (is (= "refresh" (label-of [:refresh])))
    (is (= "intent unroutable task" (label-of [:intent-unroutable {:resource/id "task"}]))))
  (testing "and the phrasings BareReplay's dock uses, unchanged"
    (is (= "connected" (label-of [:connected {:embed nil}])))
    (is (= "response accepted" (label-of [:response {:outcome :accepted}])))
    (is (= "write update #7" (label-of [:submit-write {:op :update :id "7"}])))
    (is (= "write-ack rejected" (label-of [:write-ack {:outcome :rejected}]))))
  (testing "an event the vocabulary does not hold reads as its key"
    (is (= ":some-future-event" (label-of [:some-future-event {}]))))
  (testing "a payload missing the member the label names is phrased, not thrown at"
    (is (= "response " (label-of [:response {}])))
    (is (= "write " (label-of [:submit-write {}])))))

;; disagreements --------------------------------------------------------------------------

(deftest disagreements-finds-nothing-in-a-log-that-still-means-what-it-meant
  (is (= [] (core/disagreements entries))))

(deftest disagreements-names-the-record-whose-replay-disagrees
  (testing "a tampered :after is reported, with the keys that differ and no effect complaint"
    (let [tampered (assoc-in (vec entries) [2 :after :last-accepted] :not-what-happened)
          [{:keys [probe/position resource/id probe/disagreed-keys] :as found} & more]
          (core/disagreements tampered)]
      (is (empty? more))
      (is (= 3 position))
      (is (= "tasks" id))
      (is (= [:last-accepted] disagreed-keys))
      (is (not (contains? found :probe/disagreed-effects)))))
  (testing "a tampered effect is reported even when the resource still matches"
    (let [tampered (assoc-in (vec entries) [0 :effects] [[:fetch {:url "http://elsewhere"}]])
          [{:keys [probe/position probe/disagreed-effects] :as found} & more]
          (core/disagreements tampered)]
      (is (empty? more))
      (is (= 1 position))
      (is (not (contains? found :probe/disagreed-keys)))
      (testing "as [recorded replayed]"
        (is (= [[:fetch {:url "http://elsewhere"}]] (first disagreed-effects)))
        (is (= (:effects (first entries)) (second disagreed-effects)))))))

;; diff -----------------------------------------------------------------------------------

(deftest diff-of-one-record-reads-its-own-sides
  (let [moved (get (core/diff entries 3) "tasks")]
    (is (contains? moved :last-accepted))
    (is (= [nil (:last-accepted (after 3))] (:last-accepted moved))))
  (testing "a step that moved nothing is absent, in both arities"
    (is (= {} (core/diff entries 4)))
    (is (= {} (core/diff entries 3 4)))))

(deftest diff-across-a-range-compares-two-states
  (testing "a range covers every resource that moved inside it, not only one"
    (let [moved (core/diff entries 1 3)]
      (is (= #{"tasks" "projects"} (set (keys moved))))
      (is (contains? (get moved "tasks") :last-accepted))
      (is (contains? (get moved "projects") :active-request))))
  (testing "a resource that did not move is absent"
    (is (= {} (core/diff entries 3 4))))
  (testing "a position outside the log is refused at either end of the range"
    (is (thrown? js/Error (core/diff entries 1 999)))
    (is (thrown? js/Error (core/diff entries 999 1)))))

;; at and summary -------------------------------------------------------------------------

(deftest at-is-one-based
  (is (= (first entries) (core/at entries 1)))
  (is (= (last entries) (core/at entries (count entries)))))

(deftest summary-has-one-row-per-record
  (let [rows (core/summary entries)]
    (is (= (count entries) (count rows)))
    (is (= [1 2 3 4 5] (map :probe/position rows)))
    (is (= ["tasks" "projects" "tasks" "tasks" "tasks"] (map :resource/id rows)))
    (testing "the event appears as its key, not as the event value"
      (is (= [:connected :connected :response :some-future-event :intent-patch]
             (map :probe/event-key rows))))
    (testing "moved keys agree with diff, and are ordered so rows compare by ="
      (is (= (sort-by str (keys (get (core/diff entries 3) "tasks")))
             (:probe/moved-keys (nth rows 2))))
      (is (= (:probe/moved-keys (nth rows 2))
             (:probe/moved-keys (nth (core/summary entries) 2)))))
    (testing "a step that moved nothing says so"
      (is (false? (:probe/moved? (nth rows 3))))
      (is (= [] (:probe/moved-keys (nth rows 3)))))))

(defn- nav-in
  "What `m`'s key `k` navigates to."
  [m k]
  (p/nav m k (get m k)))

(deftest summary-rows-navigate-to-their-record
  (let [row (nth (core/summary entries) 2)]
    (testing "the position resolves to the record it names"
      (is (= (core/at entries 3) (nav-in row :probe/position))))
    (testing "every other key is its own value"
      (is (= "tasks" (nav-in row :resource/id))))
    (testing "the metadata does not affect equality"
      (is (= (core/summary entries) (core/summary entries))))))

(deftest moved-keys-navigate-to-their-own-pair
  (let [row  (nth (core/summary entries) 2)
        ks   (:probe/moved-keys row)
        k    (first ks)]
    (is (= [(get (:before (core/at entries 3)) k)
            (get (:after (core/at entries 3)) k)]
           (p/nav ks 0 k)))
    (testing "which is what diff reports for that key"
      (is (= (get-in (core/diff entries 3) ["tasks" k]) (p/nav ks 0 k))))))

(deftest state-and-its-diagnostics-navigate
  (let [st (core/state entries 5)]
    (testing "the state's own position resolves to the record it names"
      (is (= (core/at entries 5) (nav-in st :probe/position))))
    (testing "each diagnostic resolves to the record that reported it"
      (let [d (first (:probe/diagnostics st))]
        (is (= (core/at entries 4) (nav-in d :probe/position))))))
  (testing "position 0 names no record and stays itself"
    (let [st (core/state entries 0)]
      (is (= 0 (nav-in st :probe/position))))))

(deftest disagreements-navigate-to-the-record-that-disagreed
  (let [tampered (assoc-in (vec entries) [2 :after :last-accepted] :not-what-happened)
        found    (first (core/disagreements tampered))]
    (is (= (core/at tampered 3) (nav-in found :probe/position)))
    (testing "and its disagreed keys navigate to [recorded replayed]"
      (let [ks (:probe/disagreed-keys found)]
        (is (= [:not-what-happened (:last-accepted (after 3))]
               (p/nav ks 0 (first ks))))))))

;; round trips ----------------------------------------------------------------------------

(deftest a-round-trip-pairs-a-request-with-its-answer
  (let [trips (core/round-trips entries)
        tasks (first (filter :request/id trips))]
    (testing "the connect that opened a read and the response that answered it are one trip"
      (is (= [1 3] (vec (:probe/positions tasks))))
      (is (= :connected (first (:probe/asked tasks))))
      (is (= :response (first (:probe/answered tasks))))
      (is (some? (:probe/sent tasks))))
    (testing "carrying the whole view the answering record handed over"
      (is (= (core/view entries 3) (:probe/handed tasks))))))

(deftest a-trip-carries-every-key-whether-or-not-it-has-one
  (testing "absence is nil rather than a missing key, so no caller reaches for contains?"
    (let [ks #{:resource/id :probe/positions :request/id :probe/asked :probe/sent
               :probe/answered :probe/handed :probe/diagnostics}]
      (doseq [t (core/round-trips entries)]
        (is (= ks (set (keys t))) (str "trip at " (vec (:probe/positions t)))))))
  (testing "and an event that opened no request says so with nil"
    (let [unknown (first (filter #(= :some-future-event (first (:probe/asked %)))
                                 (core/round-trips entries)))]
      (is (nil? (:probe/sent unknown)))
      (is (nil? (:probe/answered unknown)))
      (is (nil? (:request/id unknown)))
      (testing "while the diagnostic it reported rides the trip"
        (is (= [:unknown-event] (map :probe/code (:probe/diagnostics unknown))))))))

(deftest a-trips-positions-navigate-to-their-records
  (let [tasks (first (filter :request/id (core/round-trips entries)))
        ps    (:probe/positions tasks)]
    (is (= (core/at entries 1) (p/nav ps 0 (first ps))))
    (is (= (core/at entries 3) (p/nav ps 1 (second ps))))
    (testing "and the metadata does not affect equality"
      (is (= (core/round-trips entries) (core/round-trips entries))))))

(deftest every-record-appears-in-exactly-one-trip
  (testing "an answer to a request the log does not hold is a trip, not a record nothing reports"
    (let [orphan [{:resource/id "tasks" :el nil :event [:connected {:embed nil}]
                   :before {} :after {} :effects []}
                  {:resource/id "tasks" :el nil
                   :event [:response {:request/id "tasks:99" :outcome :accepted}]
                   :before {} :after {} :effects []}]
          trips  (core/round-trips orphan)]
      (is (= 2 (count trips)))
      (is (= [:connected :response] (map (comp first :probe/asked) trips)))))
  (testing "and the count holds for the fixture, where every answer has its request"
    (is (= (count entries)
           (reduce + (map (comp count :probe/positions) (core/round-trips entries)))))))

(deftest a-label-phrases-either-side-of-a-trip
  (testing "a failing answer carries an error and no outcome, so the phrase is what reads"
    (let [failed (play "tasks" tasks-0
                       [[:connected {:embed nil}]
                        (fn [r] [:network-failed {:request/id (read-id r)
                                                  :error {:kind :offline}}])])
          trip   (first (core/round-trips failed))]
      (is (= "connected" (core/label (:probe/asked trip))))
      (is (= "network failed" (core/label (:probe/answered trip))))
      (is (nil? (:outcome (second (:probe/answered trip))))))))

;; coverage -------------------------------------------------------------------------------

(def ^:private shaped
  "An accepted envelope whose rows say less than the shape allows: `est` is always blank and the
  status enum is never seen at `done`. `rank` is carried by every row and declared by no shape, the
  ordinary case. Every declared field is a key in every row, because BareBuild refuses an envelope
  where one is not."
  {:outcome   :accepted
   :revision  "tasks:v1"
   :query     {}
   :page-info {:page 1 :page-size 10 :total-pages 1 :total-count 2}
   :shape     {:id-key "id"
               :fields [{:key "title"  :type :string :required true}
                        {:key "est"    :type :string}
                        {:key "owner"  :type :string}
                        {:key "status" :type :string :enum ["todo" "doing" "done"]}]}
   :value     [{"id" "1" "title" "One" "est" "" "owner" "Bob"    "status" "todo"  "rank" 0}
               {"id" "2" "title" "Two" "est" "" "owner" "Carmen" "status" "doing" "rank" 1}]})

(def ^:private shaped-again
  "A second page overlapping the first at row 2, so four rows are observed and three exist."
  (assoc shaped
         :value
         [{"id" "2" "title" "Two"   "est" "" "owner" "Carmen" "status" "doing" "rank" 1}
          {"id" "3" "title" "Three" "est" "" "owner" "Dev"    "status" "todo"  "rank" 2}]))

(def ^:private shaped-entries
  (play "tasks" tasks-0
        [[:connected {:embed nil}]
         (fn [r] [:response (assoc shaped :request/id (read-id r))])
         [:refresh]
         (fn [r] [:response (assoc shaped-again :request/id (read-id r))])]))

(deftest coverage-fixture-installed
  (testing "the envelope was accepted rather than failing the contract check"
    (is (nil? (:last-failure (:after (last shaped-entries)))))
    (is (some? (:last-accepted (:after (last shaped-entries)))))))

(deftest coverage-reports-what-the-traffic-showed
  (let [tasks (get (core/coverage shaped-entries) "tasks")]
    (testing "rows are counted by id-key, so the row on both pages counts once"
      (is (= 3 (:probe/distinct-rows tasks))))
    (testing "a key in every row and never carrying a value"
      (is (= #{"est"} (:probe/never-filled tasks))))
    (testing "a key no shape declares is not reported, because every resource has some"
      (is (not (contains? tasks :probe/undeclared)))
      (is (= #{:probe/distinct-rows :probe/never-filled :probe/enum-unseen} (set (keys tasks)))))
    (testing "declared enum values the traffic never showed"
      (is (= {"status" #{"done"}} (:probe/enum-unseen tasks))))
    (testing "a field the rows filled is reported nowhere"
      (is (not (contains? (:probe/never-filled tasks) "owner"))))))

(deftest a-declared-field-missing-from-a-row-never-reaches-coverage
  (testing "BareBuild refuses the envelope, so it is a contract failure rather than coverage"
    (let [absent (update-in shaped [:value 0] dissoc "owner")
          recs   (play "tasks" tasks-0
                       [[:connected {:embed nil}]
                        (fn [r] [:response (assoc absent :request/id (read-id r))])])]
      (is (= :contract (:cause (:last-failure (:after (last recs))))))
      (is (empty? (core/coverage recs))))))

(deftest coverage-skips-resources-with-no-accepted-envelope
  (testing "a resource that only ever connected has nothing to report"
    (is (nil? (get (core/coverage entries) "projects"))))
  (testing "and one that answered does"
    (is (some? (get (core/coverage entries) "tasks")))))

;; predicates -----------------------------------------------------------------------------

(deftest predicates-select-rows
  (let [rows (core/summary entries)]
    (testing "moved-any matches any of the given keys"
      (is (= [3] (map :probe/position (filter (core/moved-any [:last-accepted]) rows))))
      (is (seq (filter (core/moved-any #{:last-accepted :url-intent}) rows))))
    (testing "diagnosed? matches only rows that reported something"
      (is (= [4] (map :probe/position (filter core/diagnosed? rows)))))
    (testing "an empty diagnostics vector is truthy, which is why diagnosed? exists"
      (is (every? some? (map :probe/diagnostics rows)))
      (is (= 1 (count (filter core/diagnosed? rows)))))))

(deftest a-selector-takes-its-keys-in-a-collection
  (let [rows (core/summary entries)]
    (testing "a row is not one, which is what passing the selector itself to filter would give it"
      (is (thrown? js/Error (core/moved-any (first rows)))))
    (testing "no keys at all answers a predicate matching nothing, rather than refusing"
      (is (= [] (filter (core/moved-any []) rows))))
    (testing "a string key is a key, because a resource holds domain keys as strings"
      (is (some? (core/moved-any ["title"]))))))
