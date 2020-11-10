import mill._, scalalib._, scalafmt._

object simplesql extends ScalaModule with ScalafmtModule {
  def scalaVersion = "0.27.0-RC1"

  object test extends Tests{
    def testFrameworks = Seq("utest.runner.Framework")
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.7.4",
      ivy"org.xerial:sqlite-jdbc:3.32.3.2"
    )
  }
}


