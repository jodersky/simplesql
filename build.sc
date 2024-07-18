import mill._, scalalib._, scalafmt._, publish._

trait Utest extends TestModule {
  def testFramework = "utest.runner.Framework"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::utest::0.8.3",
    ivy"org.xerial:sqlite-jdbc:3.32.3.2"
  )
}

object simplesql extends ScalaModule with ScalafmtModule with PublishModule {
  def scalaVersion = "3.3.3"

  def publishVersion = "0.3.0"
  def pomSettings = PomSettings(
    description = "Simple SQL queries around JDBC",
    organization = "io.crashbox",
    url = "https://github.com/jodersky/simplesql",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github("jodersky", "simplesql"),
    developers = Seq(
      Developer("jodersky", "Jakob Odersky", "https://github.com/jodersky")
    )
  )

  def ivyDeps = Agg(
    ivy"com.zaxxer:HikariCP:4.0.3", // connection pooling, provides a datasource
  )

  object test extends ScalaTests with Utest
}
