name := "nuprocess"

version := "0.1"

scalaVersion := "2.12.10"

libraryDependencies ++= Seq(
  "dev.zio"              %% "zio"             % "1.0.0-RC16"
, "net.liftweb"          %% "lift-common"     % "3.3.0"
, "io.monix"             %% "monix"           % "2.3.3"
, "io.monix"             %% "monix"           % "2.3.3"
, "com.github.pathikrit" %% "better-files"    % "3.8.0"

, "com.zaxxer"           %  "nuprocess"       % "1.2.4"
, "ch.qos.logback"       %  "logback-core"    % "1.2.3"
, "ch.qos.logback"       %  "logback-classic" % "1.2.3"
, "org.slf4j"            %  "slf4j-api"       % "1.7.25"
, "org.slf4j"            %  "jcl-over-slf4j"  % "1.7.25"
)

mainClass in Compile := Some("Main")
