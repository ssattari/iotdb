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

package org.apache.iotdb.db.metadata.id_table;

import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.DataTypeMismatchException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.id_table.entry.DeviceEntry;
import org.apache.iotdb.db.metadata.id_table.entry.DeviceIDFactory;
import org.apache.iotdb.db.metadata.id_table.entry.IDeviceID;
import org.apache.iotdb.db.metadata.id_table.entry.InsertMeasurementMNode;
import org.apache.iotdb.db.metadata.id_table.entry.SchemaEntry;
import org.apache.iotdb.db.metadata.id_table.entry.TimeseriesID;
import org.apache.iotdb.db.metadata.mnode.IMeasurementMNode;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateAlignedTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.db.utils.TypeInferenceUtils;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** id table belongs to a storage group and mapping timeseries path to it's schema */
public class IDTableHashmapImpl implements IDTable {

  // number of table slot
  private static final int NUM_OF_SLOTS = 256;
  /** logger */
  private static final Logger logger = LoggerFactory.getLogger(IDTableHashmapImpl.class);

  /**
   * 256 hashmap for avoiding rehash performance issue and lock competition device ID ->
   * (measurement name -> schema entry)
   */
  private Map<IDeviceID, DeviceEntry>[] idTables;

  /** disk schema manager to manage disk schema entry */
  private DiskSchemaManager diskSchemaManager;
  /** iotdb config */
  protected static IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  public IDTableHashmapImpl(File storageGroupDir) {
    idTables = new Map[NUM_OF_SLOTS];
    for (int i = 0; i < NUM_OF_SLOTS; i++) {
      idTables[i] = new HashMap<>();
    }
    diskSchemaManager = new AppendOnlyDiskSchemaManager(storageGroupDir);
  }

  /**
   * create aligned timeseries
   *
   * @param plan create aligned timeseries plan
   * @throws MetadataException if the device is not aligned, throw it
   */
  public synchronized void createAlignedTimeseries(CreateAlignedTimeSeriesPlan plan)
      throws MetadataException {
    DeviceEntry deviceEntry = getDeviceEntry(plan.getPrefixPath(), true);

    for (int i = 0; i < plan.getMeasurements().size(); i++) {
      PartialPath fullPath =
          new PartialPath(plan.getPrefixPath().toString(), plan.getMeasurements().get(i));
      SchemaEntry schemaEntry =
          new SchemaEntry(
              plan.getDataTypes().get(i),
              plan.getEncodings().get(i),
              plan.getCompressors().get(i),
              deviceEntry.getDeviceID(),
              fullPath,
              diskSchemaManager);
      deviceEntry.putSchemaEntry(plan.getMeasurements().get(i), schemaEntry);
    }
  }

  /**
   * create timeseries
   *
   * @param plan create timeseries plan
   * @throws MetadataException if the device is aligned, throw it
   */
  public synchronized void createTimeseries(CreateTimeSeriesPlan plan) throws MetadataException {
    DeviceEntry deviceEntry = getDeviceEntry(plan.getPath().getDevicePath(), false);
    SchemaEntry schemaEntry =
        new SchemaEntry(
            plan.getDataType(),
            plan.getEncoding(),
            plan.getCompressor(),
            deviceEntry.getDeviceID(),
            plan.getPath(),
            diskSchemaManager);
    deviceEntry.putSchemaEntry(plan.getPath().getMeasurement(), schemaEntry);
  }

  /**
   * check inserting timeseries existence and fill their measurement mnode
   *
   * @param plan insert plan
   * @return reusable device id
   * @throws MetadataException if insert plan's aligned value is inconsistent with device
   */
  public synchronized IDeviceID getSeriesSchemas(InsertPlan plan) throws MetadataException {
    PartialPath devicePath = plan.getDeviceId();
    String[] measurementList = plan.getMeasurements();
    IMeasurementMNode[] measurementMNodes = plan.getMeasurementMNodes();

    // 1. get device entry and check align
    DeviceEntry deviceEntry = getDeviceEntry(devicePath, plan.isAligned());

    // 2. get schema of each measurement
    for (int i = 0; i < measurementList.length; i++) {
      try {
        // get MeasurementMNode, auto create if absent
        try {
          IMeasurementMNode measurementMNode =
              getOrCreateMeasurementIfNotExist(deviceEntry, plan, i);

          checkDataTypeMatch(plan, i, measurementMNode.getSchema().getType());
          measurementMNodes[i] = measurementMNode;
        } catch (DataTypeMismatchException mismatchException) {
          if (!config.isEnablePartialInsert()) {
            throw mismatchException;
          } else {
            // mark failed measurement
            plan.markFailedMeasurementInsertion(i, mismatchException);
          }
        }
      } catch (MetadataException e) {
        if (IoTDB.isClusterMode()) {
          logger.debug(
              "meet error when check {}.{}, message: {}",
              devicePath,
              measurementList[i],
              e.getMessage());
        } else {
          logger.warn(
              "meet error when check {}.{}, message: {}",
              devicePath,
              measurementList[i],
              e.getMessage());
        }
        if (config.isEnablePartialInsert()) {
          // mark failed measurement
          plan.markFailedMeasurementInsertion(i, e);
        } else {
          throw e;
        }
      }
    }

    // set reusable device id
    plan.setDeviceID(deviceEntry.getDeviceID());
    // for last flushed time map
    plan.setDevicePath(devicePath);
    plan.setDeviceId(new PartialPath(deviceEntry.getDeviceID().toStringID()));

    return deviceEntry.getDeviceID();
  }

  /**
   * update latest flushed time of one timeseries
   *
   * @param timeseriesID timeseries id
   * @param flushTime latest flushed time
   * @throws MetadataException throw if this timeseries is not exist
   */
  public synchronized void updateLatestFlushTime(TimeseriesID timeseriesID, long flushTime)
      throws MetadataException {
    getSchemaEntry(timeseriesID).updateLastedFlushTime(flushTime);
  }

  /**
   * update latest flushed time of one timeseries
   *
   * @param timeseriesID timeseries id
   * @return latest flushed time of one timeseries
   * @throws MetadataException throw if this timeseries is not exist
   */
  public synchronized long getLatestFlushedTime(TimeseriesID timeseriesID)
      throws MetadataException {
    return getSchemaEntry(timeseriesID).getFlushTime();
  }

  /**
   * register trigger to the timeseries
   *
   * @param fullPath full path of the timeseries
   * @param measurementMNode the timeseries measurement mnode
   * @throws MetadataException if the timeseries is not exits
   */
  public synchronized void registerTrigger(PartialPath fullPath, IMeasurementMNode measurementMNode)
      throws MetadataException {
    boolean isAligned = measurementMNode.getParent().isAligned();
    DeviceEntry deviceEntry = getDeviceEntry(fullPath.getDevicePath(), isAligned);

    deviceEntry.getSchemaEntry(fullPath.getMeasurement()).setUsingTrigger();
  }

  /**
   * deregister trigger to the timeseries
   *
   * @param fullPath full path of the timeseries
   * @param measurementMNode the timeseries measurement mnode
   * @throws MetadataException if the timeseries is not exits
   */
  public synchronized void deregisterTrigger(
      PartialPath fullPath, IMeasurementMNode measurementMNode) throws MetadataException {
    boolean isAligned = measurementMNode.getParent().isAligned();
    DeviceEntry deviceEntry = getDeviceEntry(fullPath.getDevicePath(), isAligned);

    deviceEntry.getSchemaEntry(fullPath.getMeasurement()).setUnUsingTrigger();
  }

  /**
   * get last cache of the timeseies
   *
   * @param timeseriesID timeseries ID of the timeseries
   * @throws MetadataException if the timeseries is not exits
   */
  public synchronized TimeValuePair getLastCache(TimeseriesID timeseriesID)
      throws MetadataException {
    return getSchemaEntry(timeseriesID).getCachedLast();
  }

  /**
   * update last cache of the timeseies
   *
   * @param timeseriesID timeseries ID of the timeseries
   * @param pair last time value pair
   * @param highPriorityUpdate is high priority update
   * @param latestFlushedTime last flushed time
   * @throws MetadataException if the timeseries is not exits
   */
  public synchronized void updateLastCache(
      TimeseriesID timeseriesID,
      TimeValuePair pair,
      boolean highPriorityUpdate,
      Long latestFlushedTime)
      throws MetadataException {
    getSchemaEntry(timeseriesID).updateCachedLast(pair, highPriorityUpdate, latestFlushedTime);
  }

  /**
   * check whether a time series is exist if exist, check the type consistency if not exist, call
   * MManager to create it
   *
   * @return measurement MNode of the time series or null if type is not match
   */
  private IMeasurementMNode getOrCreateMeasurementIfNotExist(
      DeviceEntry deviceEntry, InsertPlan plan, int loc) throws MetadataException {
    String measurementName = plan.getMeasurements()[loc];
    PartialPath seriesKey = new PartialPath(plan.getDeviceId().toString(), measurementName);

    SchemaEntry schemaEntry = deviceEntry.getSchemaEntry(measurementName);

    // if not exist, we create it
    if (schemaEntry == null) {
      // we have to copy plan's mnode for using id table's last cache
      IMeasurementMNode[] insertPlanMNodeBackup =
          new IMeasurementMNode[plan.getMeasurementMNodes().length];
      System.arraycopy(
          plan.getMeasurementMNodes(), 0, insertPlanMNodeBackup, 0, insertPlanMNodeBackup.length);
      try {
        IoTDB.metaManager.getSeriesSchemasAndReadLockDevice(plan);
      } catch (IOException e) {
        throw new MetadataException(e);
      }

      // if the timeseries is in template, mmanager will not create timeseries. so we have to put it
      // in id table here
      for (IMeasurementMNode measurementMNode : plan.getMeasurementMNodes()) {
        if (measurementMNode != null && !deviceEntry.contains(measurementMNode.getName())) {
          IMeasurementSchema schema = measurementMNode.getSchema();
          SchemaEntry curEntry =
              new SchemaEntry(
                  schema.getType(),
                  schema.getEncodingType(),
                  schema.getCompressor(),
                  deviceEntry.getDeviceID(),
                  seriesKey,
                  diskSchemaManager);
          deviceEntry.putSchemaEntry(measurementMNode.getName(), curEntry);
        }
      }

      // copy back measurement mnode list
      System.arraycopy(
          insertPlanMNodeBackup, 0, plan.getMeasurementMNodes(), 0, insertPlanMNodeBackup.length);

      schemaEntry = deviceEntry.getSchemaEntry(measurementName);
    }

    // timeseries is using trigger, we should get trigger from mmanager
    if (schemaEntry.isUsingTrigger()) {
      IMeasurementMNode measurementMNode = IoTDB.metaManager.getMeasurementMNode(seriesKey);
      return new InsertMeasurementMNode(
          measurementName, schemaEntry, measurementMNode.getTriggerExecutor());
    }

    return new InsertMeasurementMNode(measurementName, schemaEntry);
  }

  /**
   * get device id from device path and check is aligned,
   *
   * @param deviceName device name of the time series
   * @param isAligned whether the insert plan is aligned
   * @return device entry of the timeseries
   */
  private DeviceEntry getDeviceEntry(PartialPath deviceName, boolean isAligned)
      throws MetadataException {
    IDeviceID deviceID = DeviceIDFactory.getInstance().getDeviceID(deviceName);
    int slot = calculateSlot(deviceID);

    DeviceEntry deviceEntry = idTables[slot].get(deviceID);
    // new device
    if (deviceEntry == null) {
      deviceEntry = new DeviceEntry(deviceID);
      deviceEntry.setAligned(isAligned);
      idTables[slot].put(deviceID, deviceEntry);

      return deviceEntry;
    }

    // check aligned
    if (deviceEntry.isAligned() != isAligned) {
      throw new MetadataException(
          String.format(
              "Timeseries under path [%s]'s align value is [%b], which is not consistent with insert plan",
              deviceName, deviceEntry.isAligned()));
    }

    // reuse device entry in map
    return deviceEntry;
  }

  /**
   * calculate slot that this deviceID should in
   *
   * @param deviceID device id
   * @return slot number
   */
  private int calculateSlot(IDeviceID deviceID) {
    return Math.abs(deviceID.hashCode()) % NUM_OF_SLOTS;
  }

  /**
   * get schema entry
   *
   * @param timeseriesID the timeseries ID
   * @return schema entry of the timeseries
   * @throws MetadataException throw if this timeseries is not exist
   */
  private SchemaEntry getSchemaEntry(TimeseriesID timeseriesID) throws MetadataException {
    IDeviceID deviceID = timeseriesID.getDeviceID();
    int slot = calculateSlot(deviceID);

    DeviceEntry deviceEntry = idTables[slot].get(deviceID);
    if (deviceEntry == null) {
      throw new MetadataException(
          "get non exist timeseries's schema entry, timeseries id is: " + timeseriesID);
    }

    SchemaEntry schemaEntry = deviceEntry.getSchemaEntry(timeseriesID.getMeasurement());
    if (schemaEntry == null) {
      throw new MetadataException(
          "get non exist timeseries's schema entry, timeseries id is: " + timeseriesID);
    }

    return schemaEntry;
  }

  // from mmanger
  private void checkDataTypeMatch(InsertPlan plan, int loc, TSDataType dataType)
      throws MetadataException {
    TSDataType insertDataType;
    if (plan instanceof InsertRowPlan) {
      if (!((InsertRowPlan) plan).isNeedInferType()) {
        // only when InsertRowPlan's values is object[], we should check type
        insertDataType = getTypeInLoc(plan, loc);
      } else {
        insertDataType = dataType;
      }
    } else {
      insertDataType = getTypeInLoc(plan, loc);
    }
    if (dataType != insertDataType) {
      String measurement = plan.getMeasurements()[loc];
      logger.warn(
          "DataType mismatch, Insert measurement {} type {}, metadata tree type {}",
          measurement,
          insertDataType,
          dataType);
      throw new DataTypeMismatchException(measurement, insertDataType, dataType);
    }
  }

  /** get dataType of plan, in loc measurements only support InsertRowPlan and InsertTabletPlan */
  private TSDataType getTypeInLoc(InsertPlan plan, int loc) throws MetadataException {
    TSDataType dataType;
    if (plan instanceof InsertRowPlan) {
      InsertRowPlan tPlan = (InsertRowPlan) plan;
      dataType =
          TypeInferenceUtils.getPredictedDataType(tPlan.getValues()[loc], tPlan.isNeedInferType());
    } else if (plan instanceof InsertTabletPlan) {
      dataType = (plan).getDataTypes()[loc];
    } else {
      throw new MetadataException(
          String.format(
              "Only support insert and insertTablet, plan is [%s]", plan.getOperatorType()));
    }
    return dataType;
  }

  @TestOnly
  public Map<IDeviceID, DeviceEntry>[] getIdTables() {
    return idTables;
  }

  @TestOnly
  public DiskSchemaManager getDiskSchemaManager() {
    return diskSchemaManager;
  }
}
