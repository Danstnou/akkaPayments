name := "untitled"

version := "0.1"

scalaVersion := "2.13.3"

val AkkaVersion = "2.6.8"

libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion

// https://mvnrepository.com/artifact/com.typesafe.akka/akka-stream
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.8"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"