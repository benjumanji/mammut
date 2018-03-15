lazy val `client` =
  project
    .in(file("client"))
    .enablePlugins(JavaAppPackaging, AshScriptPlugin)
    .settings(
      scalaVersion := "2.12.4",
      organization := "com.grandcloud.mammut",
      version := "0.1.0-SNAPSHOT",
      scalacOptions ++= Seq(
        "-unchecked",
        "-deprecation",
        "-language:_",
        "-target:jvm-1.8",
        "-encoding",
        "UTF-8"
      ),
      dockerBaseImage := "openjdk:8-jre-alpine",
      PB.targets.in(Compile) := Seq(scalapb.gen(flatPackage = true) -> sourceManaged.in(Compile).value),
      libraryDependencies ++= Seq(
        "com.trueaccord.scalapb" %% "scalapb-runtime"                       % com.trueaccord.scalapb.compiler.Version.scalapbVersion % "protobuf",
        "com.trueaccord.scalapb" %% "scalapb-runtime-grpc"                  % com.trueaccord.scalapb.compiler.Version.scalapbVersion,
        "io.grpc"                % "grpc-netty"                             % com.trueaccord.scalapb.compiler.Version.grpcJavaVersion,
        "org.bouncycastle"       % "bcprov-jdk15on"                         % "1.59"
      )
    )

