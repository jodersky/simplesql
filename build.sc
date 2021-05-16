import mill._, scalalib._, scalafmt._, publish._

object simplesql extends ScalaModule with ScalafmtModule with PublishModule {
  def scalaVersion = "3.0.0"

  def publishVersion = "0.2.0"
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

  object test extends Tests{
    def testFramework = "utest.runner.Framework"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.7.10",
      ivy"com.zaxxer:HikariCP:4.0.3", // connection pooling, provides a datasource
      ivy"org.xerial:sqlite-jdbc:3.32.3.2"
    )
  }
}
