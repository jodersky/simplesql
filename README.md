# Simple SQL

A no-frills SQL library for Scala 3.

SimpleSQL is a very thin wrapper around JDBC, which allows you to take full
advantage of *full SQL* and *any database* with a JDBC driver.

SimpleSQL is not a functional DSL to build SQL queriesq, but it does offer safe
string interpolation and utilities to work with product types, wich are the
natural representation of relational data sets.

SimpleSQL only uses Hikari for database connection pooling, but has no
dependencies otherwise (and even that can easily be removed). It is published to
maven central, under `io.crashbox:simplesql_3:0.3.0`, but **it can also be embedded by
copying the file
[simplesql/src/simplesql.scala](https://raw.githubusercontent.com/jodersky/simplesql/master/simplesql/src/simplesql.scala)
into your application**!

## Example

```scala
import simplesql as sq

// a plain DataSource is needed, this example uses a connection pool implemented
// by HicariCP
val ds: sq.DataSource.pooled("jdbc:sqlite::memory:")

// all queries must be run within the context of a connection, use either
// `<ds>.run` or `<ds>.transaction` blocks
ds.transaction:
  sql"""
    create table user (
      id integer primary key,
      name string not null,
      email string not null
    )
  """.write()

  sql"select * from user".read[(Int, String, String)]
  sql"""insert into user values (1, "admin", "admin@example.org")""".write()

  case class User(id: Int, name: String, email: String) derives sq.Reader
  sql"select * from user".read[User]
```

## Explanation

### Database connection

All queries must be run on a connection to a database. SimpleSQL models this
through a `Connection` class, which is just a simple wrapper around
`java.sql.Connection`.

A connection may be obtained as a [context
function](https://dotty.epfl.ch/docs/reference/contextual/context-functions.html)
through either `<datasource>.run` or `<datasource>.transaction`. Both functions
provide a connection, however the latter will automatically roll back any
changes, should an exception be thrown in its body.

An in-scope connection also gives access to the `sql` string interpolator. This
interpolator is a utility to build `simplesql.Query`s, which are builders for
`java.sql.PreparedStatements`. In other words, it can be used to build
injection-safe queries with interpolated parameters. Interpolated parameters
must be primitve types (supported by JDBC).

### Read Queries

Read queries (e.g. selects) must be run in a `read` call. A read must have its
result type specified. The result type may be any primitive type supported by
JDBC `ResultSet`s or a product thereof (including named products, i.e. `case
class`es).

Fields of case classes are converted to `snake_case` in the database. You can
override this by annotating them with `simplesql.col("name")`.

### Write Queries

Write queries (e.g. insertions, updates, deletes and table alterations) must be
run in a `write` call.

## Migrations

Simplesql also has an experimental module to manage database migrations. This is
included if simplesql is consumed via maven, otherwise it must be added by
copying the file in `migrations/src/simplesql/migrations/migrations.scala`.

Essentially, the module works by looking for `.sql` files in a folder on the
classpath (typically packaged in your final application jar) and applying them
"in order" to reach a specific version (see below). The module also adds a
special table to your datasource to keep track of which migrations have been
already applied.

### Defining a migration

A migration is an sql file which consists of:

- a unique name
- a reference to the version which precedes it (or "base" if nothing should precede it)
- upgrade sql statements
- downgrade sql statements

this information apart from the file name is encoded in the following way:

```sql
-- prev: <prev version>

  -- upgrade statements

-- down:

  -- downgrade statements
```

The upgrade and downgrade statements are placeholders for actual SQL. The `--
prev` and `-- down` comments have special meaning for migrations however and
must appear literally.

See migrations/test/resources/migrations/ for some example files.

### Applying a migration

```scala
val mdb = simplesql.migrations.MigrationTool.fromClasspath(
  simplesql.DataSource.pooled("jdbc:sqlite::memory:")
)

mdb.applyUp("0001_data.sql") // migrate upwards to explicit version
mdb.applyUp("head") // "head" means the "newest" version, it will fail if there are multiple newest versions
mdb.applyDown("base") // "base" is a special version which means the initial version before any migration was ever applied
```

Note: we recommend only ever downgrading for development purposes. In
production, any mistakes should always be corrected with upgrading migrations.

### Order of migrations

Each migration must have a pointer to a previous migration file. When applying a
migration, the system will first do a topological sort from the target
migration, and then apply migrations that are necessary.

Using explicit pointers instead of relying on filename order has a couple of
benefits:

- It allows branching during development.
- Since and error will occur if attempting to migrate to the head version when
  there are multiple heads, it eliminates a source of data corruption if multiple
  migrations are devloped simultanously and merged together without rebasing
  them into a linear sequence.

### Acknowledgements

The idea of branching in the migration library has been inspired from
[Alembic](https://alembic.sqlalchemy.org/en/latest/). However, instead of
allowing migration merges, we explicitly require rebases to a linear history.
Also, migration versions correspond 1-1 with file names instead of synthetic
version identifiers that are part of the file names. We believe that this allows
developers to benefit from lexicographically sorted migrations (e.g. by calling
your migrations `0000-init.sql`, `0001-foo.sql`, `0002-bar.sql` etc), but still
prevent accidental non-determinism when developing concurrently with others. It
also makes it easy to write migrations by hand without the need of a separate
too to manage them for us.

## Changelog

### 0.3.0

- Make `run` and `transaction` methods of `DataSource` instead of top-level
  functions. Also make `read` and `write()` methods on the type returned by `sql`.
  This removes an argument list in syntax. E.g. instead of

  ```scala
  sq.transaction(ds):
    sq.read[A](sql"""...""")
  ```

  the syntax is now

  ```scala
  sq.transaction:
    sql"""...""".read[A]
  ```

- Include HikariCP as a dependency for convenience

- Separate the reader hierarchy in two:

  - primitive "simple" readers read database columns and typically map to
    primitive database types
  - normal readers user tuples of simple readers to read rows and map them to
    more complex scala types

- Readers of case classes use column names instead of positions.

### 0.2.0

See git history
