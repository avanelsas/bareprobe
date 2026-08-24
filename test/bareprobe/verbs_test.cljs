(ns bareprobe.verbs-test
  "VERBS.md and the two namespaces name the same verbs. Read from disk, so adding a public var
  without documenting it, or documenting one that no longer exists, fails here."
  (:require ["fs" :as fs]
            [bareprobe.core]
            [bareprobe.live]
            [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private verbs-md
  (fs/readFileSync "docs/VERBS.md" "utf8"))

(defn- named?
  "True when VERBS.md names `sym`, as a call of any arity or as a value."
  [sym]
  (let [s (str sym)]
    (or (str/includes? verbs-md (str "`(" s " "))
        (str/includes? verbs-md (str "`(" s ")"))
        (str/includes? verbs-md (str "`" s "`")))))

(defn- undocumented
  "The public names of `ns-sym` that VERBS.md does not name."
  [names]
  (into [] (remove named?) (sort names)))

;; The other direction. The verb column of every table row is the inventory, so a name is read
;; from there rather than from the prose, where a verb is mentioned rather than documented.

(defn- verb-cells
  "The first cell of each table row in VERBS.md, the header and the rule apart."
  []
  (into []
        (comp (filter #(str/starts-with? % "|"))
              (map #(str/trim (nth (str/split % #"\|") 1 "")))
              (remove #(or (= "Verb" %) (str/starts-with? % "-"))))
        (str/split-lines verbs-md)))

(defn- verb-name
  "The symbol a code span names, or nil when it names none."
  [span]
  (some-> (re-find #"^\(?([a-z][a-zA-Z0-9*+!?<>=-]*)" (str/trim span)) second symbol))

(defn- documented
  "Every verb VERBS.md's tables name."
  []
  (into #{}
        (comp (mapcat #(map second (re-seq #"`([^`]+)`" %)))
              (keep verb-name))
        (verb-cells)))

(def ^:private publics
  (into #{} (concat (keys (ns-publics 'bareprobe.core))
                    (keys (ns-publics 'bareprobe.live)))))

(deftest verbs-md-names-every-public-verb
  (testing "bareprobe.core"
    (is (= [] (undocumented (keys (ns-publics 'bareprobe.core))))))
  (testing "bareprobe.live"
    (is (= [] (undocumented (keys (ns-publics 'bareprobe.live)))))))

(deftest verbs-md-names-nothing-that-no-longer-exists
  (is (= #{} (set/difference (documented) publics))))

;; The README and the worked session name verbs too, and a rename reaches them last. Both ship with
;; a clone, so they are read from disk the way VERBS.md is. A session form sits inside `comment`,
;; which the compiler reads and never resolves, so nothing else catches a name that moved. The
;; design record is untracked and cannot be read here.

(def ^:private readme
  (fs/readFileSync "README.md" "utf8"))

(def ^:private session
  (fs/readFileSync "src/bareprobe/session.cljs" "utf8"))

(defn- qualified-verbs
  "Every `core/x` and `live/x` name `text` uses."
  [text]
  (into #{} (map (comp symbol second)) (re-seq #"\b(?:core|live)/([a-z][a-zA-Z0-9*+!?<>=-]*)" text)))

(deftest the-readme-names-no-verb-that-does-not-exist
  (is (= #{} (set/difference (qualified-verbs readme) publics))))

(deftest the-worked-session-names-no-verb-that-does-not-exist
  (is (= #{} (set/difference (qualified-verbs session) publics))))

(deftest the-checks-fail-when-the-two-disagree
  (testing "so an empty answer above means both sides were read"
    (is (= ['no-such-verb] (undocumented ['no-such-verb 'summary])))
    (is (seq (documented)))
    (is (contains? (documented) 'summary)))
  (testing "and a cell is read whether it names a call or a bare value"
    (is (= 'summary (verb-name "(summary entries)")))
    (is (= 'a-value (verb-name "a-value"))))
  ;; The session teaches usage, so it always names verbs and is what proves the reader works. The
  ;; README refers to it rather than repeating it, so it may name few verbs or none, and a README
  ;; naming none has nothing to get wrong.
  (testing "and the session is read for names rather than matching nothing"
    (is (contains? (qualified-verbs session) 'summary))
    (is (= #{'no-such-verb} (set/difference (qualified-verbs "(core/no-such-verb x)") publics)))))
