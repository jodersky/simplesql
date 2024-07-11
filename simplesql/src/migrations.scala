package simplesql

import java.net
import java.nio.file

object migrations:

  private def foreachResource(dirName: String)(action: String => Unit): Unit =
    val res = this.getClass.getClassLoader.getResource(dirName)
    if (res == null) return

    val uri = res.toURI

    var closable: java.io.Closeable = null
    try
      if uri.getScheme == "jar" then
        val fs = file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap(), null)
        closable = fs
        file.Files.list(fs.getPath(dirName)).forEach(p => action(p.toString))
      else
        val dir = file.Paths.get(uri)
        file.Files.list(dir).forEach: p =>
          val rel = dir.relativize(p).toString
          action(dirName + "/" + rel)
    finally
      if closable != null then closable.close()

  private def listResources(dirName: String): Seq[String] =
    val buffer = collection.mutable.ListBuffer.empty[String]
    foreachResource(dirName): path =>
      buffer += path
    buffer.result()

  // list all migrations
  def listAll(dirName: String = "migrations"): Seq[String] =
    listResources(dirName).sorted

  private def ensureMeta(ds: DataSource) =
    ds.transaction:
      sql"create table if not exists simplesql_meta (key text primary key, value text)".write()
      sql"insert into simplesql_meta (key, value) values ('last_migration', null) on conflict do nothing".write()

  def getLastMigration(ds: DataSource): Option[String] =
    ensureMeta(ds)
    ds.transaction:
      sql"select value from simplesql_meta where key='last_migration'".readOne[Option[String]]

  // Run all unapplied migrations.
  def run(
      ds: DataSource,
      dirName: String = "migrations",
      report: String => Unit = s => System.err.println(s"running migration script $s")
  ): Unit =
    val scripts = getLastMigration(ds) match
      case None => listAll(dirName)
      case Some(curr) =>
        listAll(dirName).dropWhile(_ <= curr)

    if scripts.isEmpty then return

    for name <- scripts do
      ds.transaction:
        val conn = summon[Connection].underlying
        report(name)
        val stream = this.getClass.getClassLoader.getResourceAsStream(name)
        try
          val scanner = new java.util.Scanner(stream, "utf-8")
          scanner.useDelimiter(";")
          while scanner.hasNext do
            val query = scanner.next()
            if !query.isBlank then
              val stat = conn.createStatement()
              try
                stat.execute(query)
              catch
                case ex: java.sql.SQLException =>
                  throw new java.sql.SQLException(s"error running migration script $name: ${query}", ex)
              finally stat.close()
        finally stream.close()

        sql"update simplesql_meta set value=$name where key='last_migration'".write()
    end for

  end run
