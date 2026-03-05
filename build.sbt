// =============================================================
// WEBSOCKET SERVICE — build.sbt
// =============================================================

val zioVersion         = "2.0.20"
val zioKafkaVersion    = "2.7.3"
val zioHttpVersion     = "3.0.1"
val zioConfigVersion   = "4.0.0-RC16"
val zioJsonVersion     = "0.6.2"

lazy val root = (project in file("."))
  .settings(
    name := "websocket-service",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.4.0",
    organization := "com.wayrecall",

    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio"                 % zioVersion,
      "dev.zio" %% "zio-streams"         % zioVersion,

      // Kafka (GPS события и бизнес-события)
      "dev.zio" %% "zio-kafka"           % zioKafkaVersion,

      // HTTP + WebSocket сервер
      "dev.zio" %% "zio-http"            % zioHttpVersion,

      // Config
      "dev.zio" %% "zio-config"          % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,

      // JSON (сериализация WS протокола)
      "dev.zio" %% "zio-json"            % zioJsonVersion,

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",

      // Test
      "dev.zio" %% "zio-test"            % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"        % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia"   % zioVersion % Test
    ),

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-deprecation",
      "-unchecked",
      "-feature",
      "-language:postfixOps"
    ),

    assembly / mainClass := Some("com.wayrecall.tracker.websocket.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class"           => MergeStrategy.discard
      case x                             => MergeStrategy.first
    }
  )
