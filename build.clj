(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib       'com.github.avanelsas/bareprobe)
(def version   "0.2.0")
(def class-dir "target/classes")
(def jar-file  (format "target/%s-%s.jar" (name lib) version))
(def pom-file  (format "target/classes/META-INF/maven/%s/%s/pom.xml"
                       (namespace lib) (name lib)))

(def basis (delay (b/create-basis {:project "deps.edn"})))

;; `session.cljs` is a worked session rather than code. It sits under src/ so the demo's REPL can
;; reach it, compiles to nothing, and has no business in a jar.
(def jar-ignores [#".*session\.cljs$"])

(defn jar [_]
  (println "Building" jar-file "...")
  (b/delete {:path class-dir})
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/avanelsas/bareprobe"
                            :connection          "scm:git:git://github.com/avanelsas/bareprobe.git"
                            :developerConnection "scm:git:ssh://git@github.com/avanelsas/bareprobe.git"
                            :tag                 (str "v" version)}
                :pom-data  [[:description "Development-time REPL surface over BareBuild's recorded log. Reads what each step asked, sent, answered and handed to consumers, and what the page shows, as data."]
                            [:url "https://github.com/avanelsas/bareprobe"]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/licenses/MIT"]]]
                            [:developers
                             [:developer
                              [:name "Alexander van Elsas"]]]]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir
               :ignores    jar-ignores})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (b/copy-file {:src    pom-file
                :target "pom.xml"})
  (println "Done:" jar-file))

(defn deploy [_]
  (jar nil)
  (println "Deploying to Clojars...")
  (b/process {:command-args ["clojure" "-X:deploy"]}))
