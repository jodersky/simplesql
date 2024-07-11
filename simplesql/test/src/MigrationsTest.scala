import utest.*

object MigrationsTest extends TestSuite:
  val tests = Tests{
    test("migrate"){
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      simplesql.migrations.run(ds)
      simplesql.migrations.run(ds)
    }
  }
