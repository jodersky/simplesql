package simplesql.migrations

import collection.mutable as m
import scala.annotation.migration
import java.sql.SQLException
import java.nio.file

case class Migration(
  version: String,
  prev: String,
  up: String,
  down: String
):

  override def toString: String = version

object Migration:

  final private val Prev = """--\s*prev:\s*(\S*).*""".r
  final private val Down = """--\s*down.*""".r

  def fromStream(filename: String, content: java.io.InputStream) =
    val version = filename

    val reader =
      java.io.BufferedReader(
        java.io.InputStreamReader(content, "utf-8")
      )

    var line: String = null

    var prev = ""
    val up = StringBuilder()

    var isDown = false
    val down = StringBuilder()

    while
      line = reader.readLine()
      line != null
    do

      if line.trim.startsWith("--") then
        line match
          case Prev(v) => prev = v
          case Down() => isDown = true
          case _ =>
      else
        if !isDown then
          up ++= line
          up ++= "\n"
        else
          down ++= line
          down ++= "\n"

    Migration(
      version,
      prev,
      up.result(),
      down.result()
    )

  def write(migration: Migration, out: java.io.OutputStream) =
    val printer = java.io.PrintWriter(out)
    printer.println(s"-- prev: ${migration.prev}")
    printer.println()
    printer.println(migration.up)
    printer.println()
    printer.println(s"-- down:")
    printer.println()
    printer.println(migration.down)
    printer.flush()

trait Storage:

  def list(action: Migration => Unit): Unit
  def read(version: String): Option[Migration]
  def write(migration: Migration): Unit

class ClasspathStorage(dirName: String) extends Storage:

  // Perform an action for every file in a "folder" on the classpath. This works
  // regardless if the folder is an actual directory on the host filesystem or a
  // logical folder in the jar file.
  private def foreachResource(dirName: String)(action: String => Unit): Unit =
    val res = this.getClass.getClassLoader.getResource(dirName)
    if (res == null) return

    val uri = res.toURI

    var closable: java.io.Closeable = null
    try
      if uri.getScheme == "jar" then
        val fs = file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap(), null)
        closable = fs
        val dir = fs.getPath(dirName)
        file.Files.list(dir).forEach: p =>
          action(dir.relativize(p).toString)
      else
        val dir = file.Paths.get(uri)
        file.Files.list(dir).forEach: p =>
          action(dir.relativize(p).toString)
    finally
      if closable != null then closable.close()

  // list all migrations available
  private def listMigrations(dirName: String = "migrations"): Seq[Migration] =
    val buffer = collection.mutable.ListBuffer.empty[Migration]
    foreachResource(dirName): name =>
      val stream = this.getClass.getClassLoader.getResourceAsStream(dirName + "/" + name)
      try
        buffer += Migration.fromStream(name, stream)
      finally
        if stream != null then stream.close()
    buffer.result()

  def list(action: Migration => Unit): Unit =
    foreachResource(dirName): name =>
      val stream = this.getClass.getClassLoader.getResourceAsStream(dirName + "/" + name)
      try
        action(Migration.fromStream(name, stream))
      finally
        if stream != null then stream.close()

  def read(version: String): Option[Migration] =
    val path = dirName + "/" + version
    val stream = this.getClass.getClassLoader.getResourceAsStream(path)
    try
      if stream != null then Some(Migration.fromStream(version, stream)) else None
    finally
      if stream != null then stream.close()
  def write(migration: Migration): Unit = throw UnsupportedOperationException("writing migrations is not supported by the classpath storage backend")

class InMemoryStorage(migrations: Iterable[Migration]) extends Storage:
  private val data = m.Map.empty[String, Migration]
  for m <- migrations do data += m.version -> m

  def list(action: Migration => Unit): Unit = migrations.foreach(action)
  def read(version: String): Option[Migration] = data.get(version)
  def write(migration: Migration): Unit = data(migration.version) = migration

/** Methods to work with a graph of migrations */
class MigrationGraph(val storage: Storage):

  def heads: Set[Migration] =
    val heads = m.Map.empty[String, Migration]
    storage.list(m => heads += m.version -> m)
    storage.list(m => heads -= m.prev)
    heads.values.toSet

  def head: Migration =
    val hs = heads
    if hs.size != 1 then throw MigrationGraphException(
    s"multiple migration heads: ${hs.map(_.version).mkString(", ")}"
    )
    hs.head

  def walkUp(startVersion: String, targetVersion: String)(action: Migration => Unit): Unit =
    for m <- listDown(targetVersion, startVersion).reverse do action(m)

  def walkDown(startVersion: String, targetVersion: String)(action: Migration => Unit): Unit =
    for m <- listDown(startVersion, targetVersion) do action(m)

  // start inclusive, target exclusive
  def listDown(startVersion: String, targetVersion: String): List[Migration] =
    val targetVersion1 = targetVersion match
      case "head" => head.version
      case v => v

    val seen = m.Set.empty[String]
    val ordered = m.ListBuffer.empty[Migration]

    var upperVersion: String = ""
    var currVersion = startVersion match
      case "head" => head.version
      case other => other

    while currVersion != targetVersion1 && currVersion != "base" do
      val migration = storage.read(currVersion) match
        case None => throw MigrationGraphException(s"incomplete migration graph: migration '${upperVersion}' points to previous version '${currVersion}', which does not exist")
        case Some(m) => m

      if seen.contains(migration.version) then
        throw MigrationGraphException(s"migration graph contains a loop: migration ${upperVersion} points to a migration ${migration.version}, which has already been seen")
      seen += migration.version

      ordered += migration
      upperVersion = migration.version
      currVersion = migration.prev

    if currVersion != targetVersion1 then
      throw MigrationGraphException(s"migration version '${targetVersion}' cannot be reached from '${startVersion}'")
    else
      ordered.result()

  end listDown

object MigrationGraph:

  // useful for managing migrations from within your application
  def fromClasspath(dirName: String = "migrations") = MigrationGraph(
    ClasspathStorage(dirName)
  )

class MigrationGraphException(message: String) extends Exception(message)

// methods to work with a database
class MigrationTool(
  migrations: MigrationGraph,
  ds: simplesql.DataSource,
  log: String => Unit
):

  def currentVersion(): String =
    ds.run:
      sql"""create table if not exists simplesql_migration (version text not null primary key)""".write()
      sql"""select version from simplesql_migration""".readOpt[String] match
        case None => "base"
        case Some(v) => v

  /** Apply all up migrations from the current database version to and including the target. */
  def applyUp(target: String = "head") =
    migrations.walkUp(currentVersion(), target)(applyOne(true))

  /** Apply all down migrations from the current database version to and excluding the target. */
  def applyDown(target: String) =
    migrations.walkDown(currentVersion(), target)(applyOne(false))

  private def applyOne(up: Boolean)(migration: Migration): Unit =
    val script = if up then migration.up else migration.down

    log(s"running database migration $migration (${if up then "up" else "down"})")
    ds.transaction:
      val scanner = java.util.Scanner(script)
      scanner.useDelimiter(";")
      while scanner.hasNext() do
        val query = scanner.next().trim()

        if query.isBlank() then
          ()
        else
          val stat = summon[simplesql.Connection].underlying.createStatement()
          try
            query.lines().forEach(line =>
              log("    " + line)
            )
            stat.execute(query)
          catch
            case e: SQLException =>
              throw MigrationException(s"error running migration $migration (${if up then "up" else "down"})", e)
          finally
            stat.close()
      end while
      sql"""delete from simplesql_migration""".write()
      sql"""insert into simplesql_migration (version) values (${if up then migration.version else migration.prev})""".write()

object MigrationTool:

  def fromClasspath(
    ds: simplesql.DataSource,
    log: String => Unit = System.err.println,
    dirName: String = "migrations"
  ) =
    MigrationTool(
      MigrationGraph.fromClasspath(dirName),
      ds,
      log
    )

class MigrationException(message: String, cause: Exception) extends Exception(message, cause)
