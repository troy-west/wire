(defproject com.troy-west/wire "0.1.1-SNAPSHOT"
  :description "A small Clojure library for explicitly wiring together functions into declarative computation graphs."
  
  :url "https://github.com/troy-west/wire"
  
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v20.html"}

  :plugins [[lein-cljfmt "0.5.7" :exclusions [org.clojure/clojure]]
            [jonase/eastwood "0.2.5" :exclusions [org.clojure/clojure]]
            [lein-kibit "0.1.6" :exclusions [org.clojure/clojure org.clojure/tools.reader]]]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [rhizome "0.2.7"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

  :aliases {"smoke" ["do" ["clean"] ["check"] ["test"] ["kibit"] ["cljfmt" "check"] ["eastwood"]]}

  :pedantic? :abort)
