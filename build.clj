(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.datalevin/biff-datalevin)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/datalevin/biff-datalevin"
                      :connection "scm:git:git://github.com/datalevin/biff-datalevin.git"
                      :developerConnection "scm:git:ssh://git@github.com/datalevin/biff-datalevin.git"
                      :tag (str "v" version)}
                :pom-data [[:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built:" jar-file))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")}))

(comment

  ;; # Build JAR
 ;; clojure -T:build jar

 ;; # Deploy to Clojars (set CLOJARS_USERNAME and CLOJARS_PASSWORD env vars)
 ;; clojure -T:build deploy

  )
