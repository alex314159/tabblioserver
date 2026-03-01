(defproject tabblioserver "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [nrepl "1.6.0"]

                 ;Logging
                 [ch.qos.logback/logback-classic "1.5.32"]
                 [org.codehaus.janino/janino "3.1.12"]
                 [org.clojure/tools.logging "1.3.1"]

                 ;API
                 [http-kit "2.8.1"]; Our http library for client/server
                 [ring/ring-defaults "0.7.0"]; Ring defaults - for query params etc
                 [ring-cors "0.1.13"]
                 [metosin/reitit "0.10.0"]; Routing library
                 [ring/ring-json "0.5.1"]; JSON middleware
                 [cheshire "6.1.0"]; JSON parsing

                 [telegrambot-lib "2.15.0"]

                 ;SQL
                 [hikari-cp "4.0.0"]
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [org.xerial/sqlite-jdbc "3.51.2.0"]

                 ;Logging users
                 [io.github.tonsky/clj-simple-stats "1.2.0"]

                 ;Authentication
                 [com.clerk/backend-api "4.1.3"]]
  :plugins []
  :repl-options {:init-ns tabblioserver.core})
