(defproject tabblioserver "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [nrepl "1.1.1"]

                 ;Logging
                 [ch.qos.logback/logback-classic "1.5.18"]
                 [org.codehaus.janino/janino "3.1.12"]
                 [org.clojure/tools.logging "1.3.0"]

                 ;API
                 [http-kit "2.8.0"]; Our http library for client/server
                 [ring/ring-defaults "0.3.4"]; Ring defaults - for query params etc
                 [ring-cors "0.1.13"]
                 [metosin/reitit "0.9.1"]; Routing library
                 [ring/ring-json "0.5.1"]; JSON middleware
                 [cheshire "6.0.0"]; JSON parsing

                 [telegrambot-lib "2.7.0"]

                 ;SQL
                 [hikari-cp "3.2.0"]
                 [com.github.seancorfield/next.jdbc "1.3.981"]
                 [org.xerial/sqlite-jdbc "3.46.0.0"]

                 ;Authentication
                 [com.clerk/backend-api "3.2.0"]

                 ;Payments
                 [com.stripe/stripe-java "29.4.0"]

                 ;Environment variables
                 [environ "1.2.0"]

                 ]
  :repl-options {:init-ns tabblioserver.core})
