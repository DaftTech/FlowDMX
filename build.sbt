name := "FlowDMX"

lazy val settings = Seq(
  version := "0.0.0",

  scalaVersion := "2.11.8",

  resolvers := Seq("Artifactory" at "http://lolhens.no-ip.org/artifactory/libs-release/"),

  scalacOptions ++= Seq("-Xmax-classfile-name", "254")
)

lazy val common = project
  .in(file("fdmx-common"))
  .settings(settings: _*)

lazy val core = project
  .in(file("fdmx-core"))
  .enablePlugins(JavaAppPackaging, UniversalPlugin)
  .settings(settings: _*)
  .dependsOn(common)

lazy val gui = project
  .in(file("fdmx-gui"))
  .enablePlugins(JavaAppPackaging, UniversalPlugin)
  .settings(settings: _*)
  .dependsOn(common)

lazy val root = project.in(file(".")).settings(settings: _*).aggregate(core, gui)