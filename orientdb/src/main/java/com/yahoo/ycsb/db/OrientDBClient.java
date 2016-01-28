/**
 * OrientDB client binding for YCSB.
 * <p>
 * Submitted by Luca Garulli on 5/10/2012.
 */

package com.yahoo.ycsb.db;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.dictionary.ODictionary;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.yahoo.ycsb.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

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
  private ODatabaseDocumentTx  db;
  private ODictionary<ORecord> dictionary;

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

      db = new ODatabaseDocumentTx(url);
      if (db.exists()) {
        db.open(user, password);
        if (newdb) {
          System.out.println("OrientDB drop and recreate fresh db");
          db.drop();
          db.create();
        }
      } else {
        System.out.println("OrientDB database not found, create fresh db");
        db.create();
      }

      System.out.println("OrientDB connection created with " + url);

      dictionary = db.getMetadata().getIndexManager().getDictionary();
      if (!db.getMetadata().getSchema().existsClass(CLASS)) {
        db.getMetadata().getSchema().createClass(CLASS);
      }

      db.declareIntent(new OIntentMassiveInsert());

    } catch (Exception e1) {
      System.err.println("Could not initialize OrientDB connection pool for Loader: " + e1.toString());
      e1.printStackTrace();
    }

  }

  @Override
  public void cleanup() throws DBException {
    if (db != null) {
      db.close();
      db = null;
    }
  }

  @Override
  /**
   * Insert a record in the database. Any field/value pairs in the specified values
   * HashMap will be written into the record with the specified
   * record key.
   *
   * @param table The name of the table
   * @param key The record key of the record to insert.
   * @param values A HashMap of field/value pairs to insert in the record
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */ public Status insert(String table, String key, HashMap<String, ByteIterator> values) {
    try {
      final ODocument document = new ODocument(CLASS);

      for (Entry<String, String> entry : StringByteIterator.getStringMap(values).entrySet()) {
        document.field(entry.getKey(), entry.getValue());
      }

      document.save();
      dictionary.put(key, document);

      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  @Override
  /**
   * Delete a record from the database.
   *
   * @param table The name of the table
   * @param key The record key of the record to delete.
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */ public Status delete(String table, String key) {
    try {
      dictionary.remove(key);
      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  @Override
  /**
   * Read a record from the database. Each field/value pair from the result will be stored in a HashMap.
   *
   * @param table The name of the table
   * @param key The record key of the record to read.
   * @param fields The list of fields to read, or null for all of them
   * @param result A HashMap of field/value pairs for the result
   * @return Zero on success, a non-zero error code on error or "not found".
   */ public Status read(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
    try {
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

  @Override
  /**
   * Update a record in the database. Any field/value pairs in the specified values
   * HashMap will be written into the record with the specified
   * record key, overwriting any existing values with the same field name.
   *
   * @param table The name of the table
   * @param key The record key of the record to write.
   * @param values A HashMap of field/value pairs to update in the record
   * @return Zero on success, a non-zero error code on error. See this class's description f
   * or a discussion of error codes.
   */ public Status update(String table, String key, HashMap<String, ByteIterator> values) {
    try {
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

  @Override
  /**
   * Perform a range scan for a set of records in the database.
   * Each field/value pair from the result will be stored in a HashMap.
   *
   * @param table The name of the table
   * @param startkey The record key of the first record to read.
   * @param recordcount The number of records to read
   * @param fields The list of fields to read, or null for all of them
   * @param result A Vector of HashMaps, where each HashMap is a set field/value pairs for one record
   * @return Zero on success, a non-zero error code on error.
   * See this class's description for a discussion of error codes.
   */ public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    try {
      final OIndexCursor entries = dictionary.getIndex().iterateEntriesMajor(startkey, true, true);
      while (entries.hasNext()) {
        final Entry<Object, OIdentifiable> entry = entries.nextEntry();
        final ODocument document = entry.getValue().getRecord();

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
    return db;
  }
}
