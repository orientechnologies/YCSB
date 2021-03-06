/**
 * OrientDB client binding for YCSB.
 * <p>
 * Submitted by Luca Garulli on 5/10/2012.
 */

package com.yahoo.ycsb.db;

import com.orientechnologies.orient.core.db.OPartitionedDatabasePool;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.dictionary.ODictionary;
import com.orientechnologies.orient.core.exception.OSchemaNotCreatedException;
import com.orientechnologies.orient.core.exception.OStorageExistsException;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.yahoo.ycsb.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OrientDB client for YCSB framework.
 * <p>
 * Properties to set:
 * <p>
 * orientdb.url=local:C:/temp/databases or remote:localhost:2424 <br>
 * orientdb.database=ycsb <br>
 * orientdb.user=admin <br>
 * orientdb.password=admin <br>
 *
 * @author Luca Garulli
 */
public class OrientDBClient extends DB {

  private static final String CLASS = "usertable";

  private static AtomicReference<OPartitionedDatabasePool> databasePool = new AtomicReference<>();

  /**
   * Initialize any state for this DB. Called once per DB instance; there is one DB instance per client thread.
   */
  public void init() throws DBException {
    // initialize OrientDB driver
    Properties props = getProperties();

    final String tempDir = System.getProperty("java.io.tmpdir");

    String url;
    if (tempDir != null) {
      url = props.getProperty("orientdb.url", "plocal:" + tempDir +
          File.separator + "databases" + File.separator + "ycsb");
    } else {
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
        url = props.getProperty("orientdb.url", "plocal:C:/temp/databases/ycsb");
      } else {
        url = props.getProperty("orientdb.url", "plocal:/temp/databases/ycsb");
      }
    }

    String user = props.getProperty("orientdb.user", "admin");
    String password = props.getProperty("orientdb.password", "admin");
    Boolean newdb = Boolean.parseBoolean(props.getProperty("orientdb.newdb", "false"));

    try {
      System.out.println("OrientDB loading database url = " + url);

      ODatabaseDocumentTx db = new ODatabaseDocumentTx(url);
      if (newdb && db.exists()) {
        db.open(user, password);
        System.out.println("OrientDB drop and recreate fresh db");
        db.drop();
      }

      if (!db.exists()) {
        try {
          db.create();
        } catch (OStorageExistsException e) {
          System.out.println("Storage was created in parallel thread");
        }
      }

      boolean schemaInitialized = false;
      while (!schemaInitialized) {
        try {
          if (db.isClosed()) {
            db.open("admin", "admin");
          }

          if (!db.getMetadata().getSchema().existsClass(CLASS)) {
            db.getMetadata().getSchema().createClass(CLASS);
          }
          schemaInitialized = true;
        } catch (OSchemaNotCreatedException e) {
          if (!db.isClosed()) {
            db.close();
          }

          Thread.sleep(100);
        }
      }

      if (!db.isClosed()) {
        db.close();
      }

      if (databasePool.get() == null) {
        final OPartitionedDatabasePool pool = new OPartitionedDatabasePool(url, user, password);
        databasePool.compareAndSet(null, pool);
      }

    } catch (Exception e1) {
      System.err.println("Could not initialize OrientDB connection pool for Loader: " + e1.toString());
      e1.printStackTrace();
    }

  }

  @Override
  public void cleanup() throws DBException {
  }

  /**
   * Insert a record in the database. Any field/value pairs in the specified values
   * HashMap will be written into the record with the specified
   * record key.
   *
   * @param table  The name of the table
   * @param key    The record key of the record to insert.
   * @param values A HashMap of field/value pairs to insert in the record
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */
  @Override
  public Status insert(String table, String key, HashMap<String, ByteIterator> values) {
    final OPartitionedDatabasePool pool = databasePool.get();
    try (ODatabaseDocumentTx db = pool.acquire()) {
      final ODocument document = new ODocument(CLASS);

      for (Entry<String, String> entry : StringByteIterator.getStringMap(values).entrySet()) {
        document.field(entry.getKey(), entry.getValue());
      }

      document.save();
      final ODictionary<ORecord> dictionary = db.getMetadata().getIndexManager().getDictionary();
      dictionary.put(key, document);

      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Delete a record from the database.
   *
   * @param table The name of the table
   * @param key   The record key of the record to delete.
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */
  @Override
  public Status delete(String table, String key) {
    final OPartitionedDatabasePool pool = databasePool.get();
    try (ODatabaseDocumentTx db = pool.acquire()) {
      final ODictionary<ORecord> dictionary = db.getMetadata().getIndexManager().getDictionary();
      dictionary.remove(key);
      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Read a record from the database. Each field/value pair from the result will be stored in a HashMap.
   *
   * @param table  The name of the table
   * @param key    The record key of the record to read.
   * @param fields The list of fields to read, or null for all of them
   * @param result A HashMap of field/value pairs for the result
   * @return Zero on success, a non-zero error code on error or "not found".
   */
  @Override
  public Status read(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
    final OPartitionedDatabasePool pool = databasePool.get();
    try (ODatabaseDocumentTx db = pool.acquire()) {
      final ODictionary<ORecord> dictionary = db.getMetadata().getIndexManager().getDictionary();
      final ODocument document = dictionary.get(key);
      if (document != null) {
        if (fields != null) {
          for (String field : fields) {
            result.put(field, new StringByteIterator((String) document.field(field)));
          }
        } else {
          for (String field : document.fieldNames()) {
            result.put(field, new StringByteIterator((String) document.field(field)));
          }
        }
        return Status.OK;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Update a record in the database. Any field/value pairs in the specified values
   * HashMap will be written into the record with the specified
   * record key, overwriting any existing values with the same field name.
   *
   * @param table  The name of the table
   * @param key    The record key of the record to write.
   * @param values A HashMap of field/value pairs to update in the record
   * @return Zero on success, a non-zero error code on error. See this class's description f
   * or a discussion of error codes.
   */
  @Override
  public Status update(String table, String key, HashMap<String, ByteIterator> values) {
    final OPartitionedDatabasePool pool = databasePool.get();
    try (ODatabaseDocumentTx db = pool.acquire()) {
      final ODictionary<ORecord> dictionary = db.getMetadata().getIndexManager().getDictionary();
      final ODocument document = dictionary.get(key);
      if (document != null) {
        for (Entry<String, String> entry : StringByteIterator.getStringMap(values).entrySet()) {
          document.field(entry.getKey(), entry.getValue());
        }

        document.save();
        return Status.OK;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Perform a range scan for a set of records in the database.
   * Each field/value pair from the result will be stored in a HashMap.
   *
   * @param table       The name of the table
   * @param startkey    The record key of the first record to read.
   * @param recordcount The number of records to read
   * @param fields      The list of fields to read, or null for all of them
   * @param result      A Vector of HashMaps, where each HashMap is a set field/value pairs for one record
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */
  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    final OPartitionedDatabasePool pool = databasePool.get();
    try (ODatabaseDocumentTx db = pool.acquire()) {
      final ODictionary<ORecord> dictionary = db.getMetadata().getIndexManager().getDictionary();
      final OIndexCursor entries = dictionary.getIndex().iterateEntriesMajor(startkey, true, true);

      while (entries.hasNext()) {
        final ODocument document = entries.next().getRecord();

        final HashMap<String, ByteIterator> map = new HashMap<>();
        result.add(map);

        for (String field : fields) {
          map.put(field, new StringByteIterator((String) document.field(field)));
        }
      }

      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  public ODatabaseDocumentTx getDB() {
    final OPartitionedDatabasePool pool = databasePool.get();
    return pool.acquire();
  }
}
