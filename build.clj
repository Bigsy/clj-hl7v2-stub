(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.clojars.bigsy/clj-hl7v2-stub)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project "deps.edn"})
                :src-dirs ["src"]
                :scm {:url "https://github.com/Bigsy/clj-hl7v2-stub"
                      :connection "scm:git:git://github.com/Bigsy/clj-hl7v2-stub.git"
                      :developerConnection "scm:git:ssh://git@github.com/Bigsy/clj-hl7v2-stub.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A Clojure library for stubbing HL7v2 message communication in tests"]
                           [:url "https://github.com/Bigsy/clj-hl7v2-stub"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License"]
                             [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [_]
  (jar nil)
  (try 
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:installer :remote
      :artifact (b/resolve-path jar-file)
      :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
    (catch Exception e
      (throw (ex-info "Deployment failed" {:lib lib :version version} e)))))