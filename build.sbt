name := "paymentsApp"

version := "0.1"

scalaVersion := "2.12.11"

val AkkaVersion = "2.6.8"

libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % AkkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "org.iq80.leveldb" % "leveldb" % "0.12"

libraryDependencies += "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
