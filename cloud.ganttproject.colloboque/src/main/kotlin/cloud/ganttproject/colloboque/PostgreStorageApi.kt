/*
Copyright 2024 BarD Software s.r.o., Dmitry Barashev

This file is part of GanttProject Cloud.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package cloud.ganttproject.colloboque

import cloud.ganttproject.colloboque.db.project_template.tables.Projectfilesnapshot
import cloud.ganttproject.colloboque.db.project_template.tables.Transactionlog
import cloud.ganttproject.colloboque.db.project_template.tables.records.ProjectfilesnapshotRecord
import cloud.ganttproject.colloboque.db.project_template.tables.references.TRANSACTIONLOG
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sourceforge.ganttproject.storage.*
import net.sourceforge.ganttproject.task.Task
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.Schema
import org.jooq.conf.RenderNameCase
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.jooq.impl.SchemaImpl
import org.slf4j.LoggerFactory
import java.sql.Connection

class PostgreStorageApi(private val connectionFactory: PostgresConnectionFactory) : StorageApi {
  override fun initProject(projectRefid: String) {
    val schema = PostgresConnectionFactory.getSchema(projectRefid)
    connectionFactory.createSuperConnection(projectRefid).use {
      it.prepareCall("SELECT clone_schema(?, ?, ?)").use { stmt ->
        stmt.setString(1, "project_template")
        stmt.setString(2, schema)
        stmt.setBoolean(3, false)
        stmt.execute()
      }
    }
  }

  override fun getTransactionLogs(projectRefid: ProjectRefid, baseTxnId: BaseTxnId): List<XlogRecord> =
    txn(projectRefid) { db ->
      val logTable = TransactionLogTable(getOrCreateProjectSchema(projectRefid))
      val transactions = if (baseTxnId != NULL_TXN_ID) {
        db
          .select(logTable.LOG_RECORD_JSON)
          .from(logTable)
          .where(logTable.BASE_TXN_ID.ge(baseTxnId))
          .orderBy(logTable.BASE_TXN_ID, logTable.LOG_RECORD_NUM).fetch()
      } else {
        db
          .select(logTable.LOG_RECORD_JSON)
          .from(logTable)
          .orderBy(logTable.BASE_TXN_ID, logTable.LOG_RECORD_NUM).fetch()
      }
      transactions.map { result ->
        val stringLog = result[0] as String
        Json.decodeFromString<XlogRecord>(stringLog)
      }
    }

  override fun insertXlogs(projectRefid: ProjectRefid, baseTxnId: BaseTxnId, xlog: List<XlogRecord>) =
    txn(projectRefid) { db ->
      val logTable = TransactionLogTable(getOrCreateProjectSchema(projectRefid))
      xlog.forEachIndexed { num, xlogRecord ->
        LOG.debug("Inserting log record with baseTxn={}, num={}, record={}", baseTxnId, num, xlogRecord)
        db.insertInto(logTable, TRANSACTIONLOG.BASE_TXN_ID, TRANSACTIONLOG.LOG_RECORD_NUM, TRANSACTIONLOG.LOG_RECORD_JSON)
          .values(baseTxnId, num, Json.encodeToString(xlogRecord))
          .execute()
      }
    }


  override fun insertTask(projectRefid: String, task: Task) {
    txn(projectRefid) {
      buildInsertTaskQuery(it, task).execute()
    }
  }

  override fun getProjectSnapshot(projectRefid: String, baseTxnId: BaseTxnId?): ProjectfilesnapshotRecord? {
    val snapshotTable = ProjectFileSnapshot(getOrCreateProjectSchema(projectRefid))
    return txn(projectRefid) { db ->
      val query = baseTxnId?.let {
        db.selectFrom(snapshotTable).where(snapshotTable.BASE_TXN_ID.eq(it))
      } ?: run {
        db.selectFrom(snapshotTable).where(
          snapshotTable.BASE_TXN_ID.eq(db.select(DSL.max(snapshotTable.BASE_TXN_ID)).from(snapshotTable))
        )
      }
      query.fetchOne()
    }
  }

  override fun insertActualSnapshot(projectRefid: String, baseTxnId: BaseTxnId, projectXml: String) {
    val snapshotTable = ProjectFileSnapshot(getOrCreateProjectSchema(projectRefid))
    return txn(projectRefid) { db ->
      db.insertInto(snapshotTable).columns(snapshotTable.BASE_TXN_ID, snapshotTable.PROJECT_XML).values(baseTxnId, projectXml).execute()
    }
  }

  fun dsl(cxn: Connection): DSLContext = DSL.using(cxn, SQLDialect.POSTGRES)
    .configuration().deriveSettings { it.withRenderNameCase(RenderNameCase.LOWER) }
    .dsl()
  fun <T> txn(projectRefid: ProjectRefid, code: (DSLContext)->T): T {
    return connectionFactory.createConnection(projectRefid).use {cxn -> dsl(cxn).transactionResult { it -> code(it.dsl()) }
    }
  }

  fun createProjectSnapshotDatabase(projectXml: String, baseTxnId: BaseTxnId): SqlProjectDatabaseImpl {
    val database = SqlProjectDatabaseImpl(connectionFactory.createTemporaryDataSource(), dialect = SQLDialect.POSTGRES, initScript2 = null)
    projectFromXml(projectXml, baseTxnId) { database }
    return database
  }

  override fun tryMergeConcurrentUpdates(
    database: SqlProjectDatabaseImpl,
    serverTransaction: List<XlogRecord>,
    clientTransaction: List<XlogRecord>
  ): Boolean {
    val serverConnection = database.createConnection()
    val clientConnection = database.createConnection()
    serverConnection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    clientConnection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    val serverDsl = dsl(serverConnection)
    val clientDsl = dsl(clientConnection)
    serverDsl.startTransaction().execute()
    clientDsl.startTransaction().execute()
    try {
      serverTransaction.forEach {
        it.colloboqueOperations.forEach {
          LOG.debug("... applying operation={}", it)
          serverDsl.execute(generateSqlStatement(serverDsl, it))
        }
      }
      clientTransaction.forEach {
        it.colloboqueOperations.forEach {
          LOG.debug("... applying operation={}", it)
          // Hangs here. When `serverDsl.commit().execute()` is moved
          // before the `clientTransaction.forEach { ... }` block, the whole
          // function executes successfully and returns true even if
          // there should have been a conflict. Transaction isolation level
          // setting doesn't affect this behaviour
          clientDsl.execute(generateSqlStatement(clientDsl, it))
        }
      }
      LOG.debug("... commiting server's transaction")
      if (serverDsl.commit().execute() != 0) return false
      LOG.debug("... commiting client's transaction")
      return clientDsl.commit().execute() == 0
    } catch (e: Exception) {
      LOG.info("Failed to execute transactions in parallel! Reason: ${e.localizedMessage}")
    }
    return false
  }

  override fun getProjectXml(projectRefid: ProjectRefid, baseTxnId: BaseTxnId): String {
    return txn(projectRefid) {db ->
      val projectFileSnapshot = ProjectFileSnapshot(projectRefid)
      db.select(projectFileSnapshot.PROJECT_XML)
        .from(projectFileSnapshot)
        .where(projectFileSnapshot.BASE_TXN_ID.eq(baseTxnId))
        .fetch().getValue(0, projectFileSnapshot.PROJECT_XML)
      ?: throw RuntimeException("Can't find project xml for $projectRefid with baseTxnId $baseTxnId")
    }
  }

  private fun getOrCreateProjectSchema(projectRefid: String): String {
    val schemaName = PostgresConnectionFactory.getSchema(projectRefid)
    val hasSchema = connectionFactory.createSuperConnection(projectRefid).use {
      it.prepareCall("SELECT schema_name FROM information_schema.schemata WHERE schema_name=?").use { stmt ->
        stmt.setString(1, schemaName)
        stmt.executeQuery().use { rs ->
          rs.next()
        }
      }
    }
    if (!hasSchema) {
      initProject(projectRefid)
    }
    return schemaName
  }
}

internal class TransactionLogTable(private val schemaName: String) : Transactionlog() {
  override fun getSchema(): Schema {
    return SchemaImpl(schemaName)
  }
}

internal class ProjectFileSnapshot(private val schemaName: String) : Projectfilesnapshot() {
  override fun getSchema(): Schema {
    return SchemaImpl(schemaName)
  }
}

private val NULL_TXN_ID = 0L
private val LOG = LoggerFactory.getLogger("Postgres.StorageApi")