val scala3Version = "3.4.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "maintenance-service",
    version := "0.1.0",
    scalaVersion := scala3Version,

    // ============================================
    // Зависимости
    // ============================================
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio"                 % "2.0.20",
      "dev.zio" %% "zio-streams"         % "2.0.20",

      // HTTP
      "dev.zio" %% "zio-http"            % "3.0.0-RC4",

      // JSON
      "dev.zio" %% "zio-json"            % "0.6.2",

      // Config
      "dev.zio" %% "zio-config"          % "4.0.0-RC16",
      "dev.zio" %% "zio-config-typesafe" % "4.0.0-RC16",
      "dev.zio" %% "zio-config-magnolia" % "4.0.0-RC16",

      // Kafka
      "dev.zio" %% "zio-kafka"           % "2.2.0",

      // Redis
      "dev.zio" %% "zio-redis"           % "1.0.0-RC1",

      // Database (Doobie)
      "org.tpolecat" %% "doobie-core"    % "1.0.0-RC4",
      "org.tpolecat" %% "doobie-hikari"  % "1.0.0-RC4",
      "dev.zio" %% "zio-interop-cats"    % "23.1.0.0",

      // PostgreSQL
      "org.postgresql" % "postgresql"     % "42.7.1",
      "com.zaxxer"     % "HikariCP"      % "5.1.0",

      // Logging
      "dev.zio"       %% "zio-logging"            % "2.1.16",
      "dev.zio"       %% "zio-logging-slf4j2"     % "2.1.16",
      "ch.qos.logback" % "logback-classic"        % "1.4.14",

      // Test
      "dev.zio" %% "zio-test"            % "2.0.20" % Test,
      "dev.zio" %% "zio-test-sbt"        % "2.0.20" % Test
    ),

    // ============================================
    // Assembly (fat JAR)
    // ============================================
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "reference.conf"                     => MergeStrategy.concat
      case "application.conf"                   => MergeStrategy.concat
      case _                                    => MergeStrategy.first
    },
    assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar",

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
