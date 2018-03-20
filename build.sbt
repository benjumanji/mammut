import com.trueaccord.scalapb.compiler.{ Version => SpbVersion }

lazy val common =
  Seq(
    scalaVersion := "2.12.4",
    organization := "com.grandcloud.mammut",
    version := "0.1.0-SNAPSHOT",
    scalacOptions ++= Seq(
      "-Xlint",
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8"
    )
  )

lazy val `protocol` = 
  project
    .in(file("protocol"))
    .settings(common)
    .settings(
      PB.targets.in(Compile) := Seq(scalapb.gen(flatPackage = true) -> sourceManaged.in(Compile).value),
      libraryDependencies ++= Seq(
        "ch.qos.logback"             %  "logback-classic"                       % "1.2.3",
        "com.trueaccord.scalapb"     %% "scalapb-runtime"                       % SpbVersion.scalapbVersion % "protobuf",
        "com.trueaccord.scalapb"     %% "scalapb-runtime-grpc"                  % SpbVersion.scalapbVersion,
        "com.typesafe.scala-logging" %% "scala-logging"                         % "3.8.0",
        "io.grpc"                    %  "grpc-netty"                            % SpbVersion.grpcJavaVersion,
        "io.monix"                   %% "monix"                                 % "3.0.0-RC1",
        "org.bouncycastle"           %  "bcprov-jdk15on"                        % "1.59"
      )
    )

lazy val `client` =
  project
    .in(file("client"))
    .enablePlugins(JavaAppPackaging, AshScriptPlugin)
    .dependsOn(`protocol`)
    .settings(common)
    .settings(dockerBaseImage := "openjdk:8-jre-alpine")

lazy val `server` =
  project
    .in(file("server"))
    .enablePlugins(JavaAppPackaging, AshScriptPlugin)
    .dependsOn(`protocol`)
    .settings(common)
    .settings(
      dockerBaseImage := "openjdk:8-jre-alpine",
      libraryDependencies ++= Seq(
        "io.circe"               %% "circe-core"                            % "0.9.1",
        "io.circe"               %% "circe-generic"                         % "0.9.1",
        "io.circe"               %% "circe-parser"                          % "0.9.1",
        "io.taig"                %% "communicator-request"                  % "3.3.0",
        "io.taig"                %% "communicator-builder"                  % "3.3.0",
        "org.rocksdb"            %  "rocksdbjni"                            % "5.11.3"
      )
    )
