resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.7.3")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.13")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.0")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
