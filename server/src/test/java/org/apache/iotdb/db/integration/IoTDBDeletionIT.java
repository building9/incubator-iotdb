/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.jdbc.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IoTDBDeletionIT {

  private static String[] creationSqls = new String[]{
      "SET STORAGE GROUP TO root.vehicle.d0", "SET STORAGE GROUP TO root.vehicle.d1",
      "CREATE TIMESERIES root.vehicle.d0.s0 WITH DATATYPE=INT32, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s1 WITH DATATYPE=INT64, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s2 WITH DATATYPE=FLOAT, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s3 WITH DATATYPE=TEXT, ENCODING=PLAIN",
      "CREATE TIMESERIES root.vehicle.d0.s4 WITH DATATYPE=BOOLEAN, ENCODING=PLAIN",
  };

  private String insertTemplate = "INSERT INTO root.vehicle.d0(timestamp,s0,s1,s2,s3,s4"
      + ") VALUES(%d,%d,%d,%f,%s,%b)";
  private String deleteAllTemplate = "DELETE FROM root.vehicle.d0 WHERE time <= 10000";
  private long prevPartitionInterval;

  @Before
  public void setUp() throws Exception {
    Locale.setDefault(Locale.ENGLISH);
    EnvironmentUtils.closeStatMonitor();
    prevPartitionInterval = IoTDBDescriptor.getInstance().getConfig().getPartitionInterval();
    IoTDBDescriptor.getInstance().getConfig().setPartitionInterval(1000);
    EnvironmentUtils.envSetUp();
    Class.forName(Config.JDBC_DRIVER_NAME);
    prepareSeries();
  }

  @After
  public void tearDown() throws Exception {
    EnvironmentUtils.cleanEnv();
    IoTDBDescriptor.getInstance().getConfig().setPartitionInterval(prevPartitionInterval);
  }

  @Test
  public void test() throws SQLException {
    prepareData();
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {

      statement.execute("DELETE FROM root.vehicle.d0.s0  WHERE time <= 300");
      statement.execute("DELETE FROM root.vehicle.d0.s1,root.vehicle.d0.s2,root.vehicle.d0.s3"
          + " WHERE time <= 350");
      statement.execute("DELETE FROM root.vehicle.d0 WHERE time <= 150");

      try (ResultSet set = statement.executeQuery("SELECT * FROM root.vehicle.d0")) {
        int cnt = 0;
        while (set.next()) {
          cnt++;
        }
        assertEquals(250, cnt);
      }

      try (ResultSet set = statement.executeQuery("SELECT s0 FROM root.vehicle.d0")) {
        int cnt = 0;
        while (set.next()) {
          cnt++;
        }
        assertEquals(100, cnt);
      }

      try (ResultSet set = statement.executeQuery("SELECT s1,s2,s3 FROM root.vehicle.d0")) {
        int cnt = 0;
        while (set.next()) {
          cnt++;
        }
        assertEquals(50, cnt);
      }

    }
    cleanData();
  }

  @Test
  public void testMerge() throws SQLException {
    prepareMerge();

    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {
      statement.execute("merge");
      statement.execute("DELETE FROM root.vehicle.d0 WHERE time <= 15000");

      // before merge completes
      try (ResultSet set = statement.executeQuery("SELECT * FROM root.vehicle.d0")) {
        int cnt = 0;
        while (set.next()) {
          cnt++;
        }
        assertEquals(5000, cnt);
      }

      // after merge completes
      try (ResultSet set = statement.executeQuery("SELECT * FROM root.vehicle.d0")) {
        int cnt = 0;
        while (set.next()) {
          cnt++;
        }
        assertEquals(5000, cnt);
      }
      cleanData();
    }
  }

  @Test
  public void testDelAfterFlush() throws SQLException {
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {
      statement.execute("SET STORAGE GROUP TO root.ln.wf01.wt01");
      statement.execute("CREATE TIMESERIES root.ln.wf01.wt01.status WITH DATATYPE=BOOLEAN,"
          + " ENCODING=PLAIN");
      statement.execute("INSERT INTO root.ln.wf01.wt01(timestamp,status) "
          + "values(1509465600000,true)");
      statement.execute("INSERT INTO root.ln.wf01.wt01(timestamp,status) VALUES(NOW(), false)");

      statement.execute("delete from root.ln.wf01.wt01.status where time <= NOW()");
      statement.execute("flush");
      statement.execute("delete from root.ln.wf01.wt01.status where time <= NOW()");

      try (ResultSet resultSet = statement.executeQuery("select status from root.ln.wf01.wt01")) {
        assertFalse(resultSet.next());
      }
    }
  }


  private static void prepareSeries() {
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {

      for (String sql : creationSqls) {
        statement.execute(sql);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void prepareData() throws SQLException {
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {

      // prepare BufferWrite file
      for (int i = 201; i <= 300; i++) {
        statement.execute(
            String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
                i % 2 == 0));
      }
      statement.execute("merge");
      // prepare Unseq-File
      for (int i = 1; i <= 100; i++) {
        statement.execute(
            String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
                i % 2 == 0));
      }
      statement.execute("merge");
      // prepare BufferWrite cache
      for (int i = 301; i <= 400; i++) {
        statement.execute(
            String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
                i % 2 == 0));
      }
      // prepare Overflow cache
      for (int i = 101; i <= 200; i++) {
        statement.execute(
            String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
                i % 2 == 0));
      }

    }
  }

  private void cleanData() throws SQLException {
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {
      statement.execute(deleteAllTemplate);
    }
  }

  public void prepareMerge() throws SQLException {
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root",
            "root");
        Statement statement = connection.createStatement()) {

      // prepare BufferWrite data
      for (int i = 10001; i <= 20000; i++) {
        statement.execute(String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
            i % 2 == 0));
      }
      // prepare Overflow data
      for (int i = 1; i <= 10000; i++) {
        statement.execute(String.format(insertTemplate, i, i, i, (double) i, "\'" + i + "\'",
            i % 2 == 0));
      }

    }
  }
}
