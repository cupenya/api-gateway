import ReleaseTransformations._
import com.typesafe.sbt.packager.docker.Cmd

import scala.sys.process._

name          := """api-gateway"""
organization  := "com.github.cupenya"
scalaVersion  := "2.13.2"
scalacOptions := Seq("-unchecked", "-feature", "-deprecation", "-encoding", "utf8")

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

resolvers += Resolver.jcenterRepo
resolvers += "Cupenya Nexus" at "https://test.cupenya.com/nexus/content/groups/public"

libraryDependencies ++= {
  val akkaV            = "2.6.5"
  val akkaHttpV	       = "10.1.12"
  val ficusV           = "1.2.4"
  val scalaTestV       = "3.1.2"
  val scalaLoggingV    = "3.9.2"
  val logbackV         = "1.2.3"
  Seq(
    "com.typesafe.akka"          %% "akka-http"             % akkaHttpV,
    "com.typesafe.akka"          %% "akka-http-spray-json"  % akkaHttpV,
    "com.typesafe.akka"          %% "akka-stream"           % akkaV,
    "com.typesafe.akka"          %% "akka-slf4j"            % akkaV,
    "com.typesafe.scala-logging" %% "scala-logging"         % scalaLoggingV,
    "ch.qos.logback"              %  "logback-classic"      % logbackV,
    "org.scalatest"              %% "scalatest"             % scalaTestV  % Test
  )
}

val branch = "git rev-parse --abbrev-ref HEAD" !!
val cleanBranch = branch.toLowerCase.replaceAll(".*(cpy-[0-9]+).*", "$1").replaceAll("\\n", "").replaceAll("\\r", "")

// begin docker template settings
enablePlugins(JavaServerAppPackaging)
enablePlugins(DockerPlugin)

publishArtifact in (Compile, packageDoc) := false

val shortCommit = ("git rev-parse --short HEAD" !!).replaceAll("\\n", "").replaceAll("\\r", "")


packageName in Docker := "cpy-docker-test/" + name.value
version in Docker     := shortCommit
dockerBaseImage       := "openjdk:8u252-jre"
defaultLinuxInstallLocation in Docker := s"/opt/${name.value}" // to have consistent directory for files
dockerRepository := Some("eu.gcr.io")
// end docker template settings


Revolver.settings

initialCommands := """|import akka.actor._
                      |import akka.pattern._
                      |import akka.util._
                      |import scala.concurrent._
                      |import scala.concurrent.duration._""".stripMargin

publishMavenStyle := true
publishArtifact in Test := false
releasePublishArtifactsAction := PgpKeys.publishSigned.value
pomIncludeRepository := { _ => false }
credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
publishTo := {
  val nexus = "https://test.cupenya.com/nexus/content/repositories"
  Some("snapshots" at nexus + "/snapshots")
}
pomExtra :=
  <url>https://github.com/cupenya/api-gateway</url>
  <licenses>
    <license>
      <name>Apache-2.0</name>
      <url>http://opensource.org/licenses/Apache-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>https://github.com/cupenya/api-gateway</url>
    <connection>scm:git:git@github.com:cupenya/api-gateway.git</connection>
  </scm>
  <developers>
    <developer>
      <id>cupenya</id>
    <name>Jeroen Rosenberg</name>
      <url>https://github.com/cupenya/</url>
    </developer>
  </developers>

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(action = Command.process("publishSigned", _)),
  setNextVersion,
  commitNextVersion,
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
  pushChanges
)
