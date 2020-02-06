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

package org.apache.iotdb.cluster.partition;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.log.Log;
import org.apache.iotdb.cluster.log.logtypes.PhysicalPlanLog;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.*;
import org.apache.iotdb.db.qp.physical.sys.*;
import org.apache.iotdb.db.qp.physical.sys.ShowPlan.ShowContentType;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PartitionTable manages the map whose key is the StorageGroupName with a time interval and the
 * value is a PartitionGroup with contains all nodes that manage the corresponding data.
 */
public interface PartitionTable {
  // static final is not necessary, it is redundant for an interface
  Logger logger = LoggerFactory.getLogger(SlotPartitionTable.class);
  long PARTITION_INTERVAL = StorageEngine.getTimePartitionInterval();

  /**
   * Given the storageGroupName and the timestamp, return the list of nodes on which the storage
   * group and the corresponding time interval is managed.
   * @param storageGroupName
   * @param timestamp
   * @return
   */
  PartitionGroup route(String storageGroupName, long timestamp);

  /**
   * get a unicode value for a sg and a timestamp.
   * @param storageGroupName
   * @param timestamp
   * @return
   */
  int getSlot(String storageGroupName, long timestamp);

  PartitionGroup route(int slot);
  /**
   * Add a new node to update the partition table.
   * @param node
   * @return the new group generated by the node
   */
  PartitionGroup addNode(Node node);

  /**
   *
   * @return All data groups where all VNodes of this node is the header. The first index
   * indicates the VNode and the second index indicates the data group of one VNode.
   */
  List<PartitionGroup> getLocalGroups();

  /**
   *
   * @param header
   * @return the partition group starting from the header.
   */
  PartitionGroup getHeaderGroup(Node header);

  ByteBuffer serialize();

  void deserialize(ByteBuffer buffer);

  List<Node> getAllNodes();

  /**
   *
   * @return each slot's previous holder after the node's addition.
   */
  Map<Integer, Node> getPreviousNodeMap(Node node);

  /**
   *
   * @param header
   * @return the slots held by the header.
   */
  List<Integer> getNodeSlots(Node header);

  Map<Node, List<Integer>> getAllNodeSlots();

  int getTotalSlotNumbers();

  //==============================================================================================//
  //All the follwoing are default methods.
  //==============================================================================================//

  default int calculateLogSlot(Log log) {
    if (log instanceof PhysicalPlanLog) {
      PhysicalPlanLog physicalPlanLog = ((PhysicalPlanLog) log);
      PhysicalPlan plan = physicalPlanLog.getPlan();
      String storageGroup = null;
      if (plan instanceof CreateTimeSeriesPlan) {
        try {
          storageGroup = MManager.getInstance()
              .getStorageGroupNameByPath(((CreateTimeSeriesPlan) plan).getPath().getFullPath());
          //timestamp is meaningless, use 0 instead.
          return PartitionUtils.calculateStorageGroupSlot(storageGroup, 0, this.getTotalSlotNumbers());
        } catch (MetadataException e) {
          logger.error("Cannot find the storage group of {}", ((CreateTimeSeriesPlan) plan).getPath());
          return -1;
        }
      } else if (plan instanceof InsertPlan || plan instanceof BatchInsertPlan) {
        try {
          storageGroup = MManager.getInstance()
              .getStorageGroupNameByPath(((InsertPlan) plan).getDeviceId());
        } catch (StorageGroupNotSetException e) {
          logger.error("Cannot find the storage group of {}", ((CreateTimeSeriesPlan) plan).getPath());
          return -1;
        }
      } else if (plan instanceof DeletePlan) {
        //TODO deleteplan may have many SGs.
        logger.error("not implemented for DeletePlan in cluster {}", plan);
        return -1;
      }

      return Math.abs(Objects.hash(storageGroup, 0));
    }
    return 0;
  }

  default PartitionGroup routePlan(PhysicalPlan plan)
      throws UnsupportedPlanException, StorageGroupNotSetException, IllegalPathException {
    //the if clause can be removed after the program is stable
    if (PartitionUtils.isLocalPlan(plan)) {
      logger.error("{} is a local plan. Please run it locally directly", plan);
    } else if (PartitionUtils.isGlobalPlan(plan)) {
      logger.error("{} is a global plan. Please forward it to all partitionGroups", plan);
    }
    if (plan.canbeSplit()) {
      logger.error("{} can be split. Please call splitPlanAndMapToGroups");
    }
    throw new UnsupportedPlanException(plan);
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(PhysicalPlan plan)
      throws UnsupportedPlanException, StorageGroupNotSetException , IllegalPathException {
    //the if clause can be removed after the program is stable
    if (PartitionUtils.isLocalPlan(plan)) {
      logger.error("{} is a local plan. Please run it locally directly", plan);
    } else if (PartitionUtils.isGlobalPlan(plan)) {
      logger.error("{} is a global plan. Please forward it to all partitionGroups", plan);
    }
    if (!plan.canbeSplit()) {
      logger.error("{} cannot be split. Please call routePlan");
    }
    throw new UnsupportedPlanException(plan);
  }

  //CRUD

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(QueryPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    return null;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(AggregationPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    return null;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(DeletePlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    return null;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(FillQueryPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    return null;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(GroupByPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    return null;
  }

  default PartitionGroup routePlan(InsertPlan plan)
      throws StorageGroupNotSetException {
    return partitionByPathTime(plan.getDeviceId(), plan.getTime());
  }

  default PartitionGroup routePlan(CreateTimeSeriesPlan plan)
      throws StorageGroupNotSetException {
    return partitionByPathTime(plan.getPath().getFullPath(), 0);
  }


  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(BatchInsertPlan plan)
      throws StorageGroupNotSetException {
    String storageGroup = MManager.getInstance().getStorageGroupNameByPath(plan.getDeviceId());
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    MultiKeyMap<Long, PartitionGroup> timeRangeMapRaftGroup = new MultiKeyMap<>();
    long[] times = plan.getTimes();

    long startTime = (times[0] / PARTITION_INTERVAL ) * PARTITION_INTERVAL;//included
    long endTime = startTime + PARTITION_INTERVAL;//excluded
    int startLoc = 0; //included

    //Map<PartitionGroup>
    Map<PartitionGroup, List<Integer>> splitMap = new HashMap<>();
    //for each List in split, they are range1.start, range.end, range2.start, range2.end, ...
    for (int i = 1; i < times.length; i++) {// times are sorted in session API.
      if (times[i] >= endTime) {
        // a new range.
        PartitionGroup group = route(storageGroup, startTime);
        List<Integer> ranges = splitMap.computeIfAbsent(group, x -> new ArrayList<>());
        ranges.add(startLoc);//includec
        ranges.add(i);//excluded
        //next init
        startLoc = i;
        startTime = endTime;
        endTime = (times[i] / PARTITION_INTERVAL + 1) * PARTITION_INTERVAL;
      }
    }
    //the final range
    PartitionGroup group = route(storageGroup, startTime);
    List<Integer> ranges = splitMap.computeIfAbsent(group, x -> new ArrayList<>());
    ranges.add(startLoc);//includec
    ranges.add(times.length);//excluded

    List<Integer> locs;
    for(Map.Entry<PartitionGroup, List<Integer>> entry : splitMap.entrySet()) {
      //generate a new times and values
      locs = entry.getValue();
      int count = 0;
      for (int i = 0; i < locs.size(); i += 2) {
        int start = locs.get(i);
        int end = locs.get(i + 1);
        count += end - start;
      }
      long[] subTimes = new long[count];
      int destLoc = 0;
      Object[] values = new Object[plan.getMeasurements().length];
      for (int i = 0; i < values.length; i ++) {
        switch (plan.getDataTypes()[i]) {
          case TEXT:
            values[i] = new Binary[count];
            break;
          case FLOAT:
            values[i] = new float[count];
            break;
          case INT32:
            values[i] = new int[count];
            break;
          case INT64:
            values[i] = new long[count];
            break;
          case DOUBLE:
            values[i] = new double[count];
            break;
          case BOOLEAN:
            values[i] = new boolean[count];
            break;
        }
      }
      for (int i = 0; i < locs.size(); i += 2) {
        int start = locs.get(i);
        int end = locs.get(i + 1);
        System.arraycopy(plan.getTimes(), start, subTimes, destLoc, end - start);
        for (int k = 0; k < values.length; k ++) {
          System.arraycopy(plan.getColumns()[k], start, values[k], destLoc, end - start);
        }
        destLoc += end -start;
      }
      BatchInsertPlan newBatch = PartitionUtils.copy(plan, subTimes, values);
      result.put(newBatch, entry.getKey());
    }
    return result;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(UpdatePlan plan)
      throws UnsupportedPlanException {
    logger.error("UpdatePlan is not implemented");
    throw new UnsupportedPlanException(plan);
  }


  //SYS

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(AuthorPlan plan)
      throws UnsupportedPlanException {
    logger.error("AuthorPlan is a global plan");
    throw new UnsupportedPlanException(plan);
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(CountPlan plan)
      throws StorageGroupNotSetException, IllegalPathException {
    //CountPlan is quite special because it has the behavior of wildcard at the tail of the path
    // even though there is no wildcard
    Map<String, String> sgPathMap = MManager.getInstance().determineStorageGroup(plan.getPath().getFullPath()+".*");
    if (sgPathMap.isEmpty()) {
     throw new StorageGroupNotSetException(plan.getPath().getFullPath());
    }
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    if (plan.getShowContentType().equals(ShowContentType.COUNT_TIMESERIES)) {
      //support wildcard
      for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
        CountPlan plan1 = new CountPlan(ShowContentType.COUNT_TIMESERIES, new Path(entry.getValue()), plan.getLevel());
        result.put(plan1, route(entry.getKey(), 0));
      }
    } else {
      //do support wildcard
      if (sgPathMap.size() == 1) {
        // the path of the original plan has only one SG, or there is only one SG in the system.
        for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
          //actually, there is only one entry
          result.put(plan, route(entry.getKey(), 0));
        }
      } else {
        // the path of the original plan contains more than one SG, and we added a wildcard at the tail.
        // we have to remove it.
        for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
          CountPlan plan1 = new CountPlan(ShowContentType.COUNT_TIMESERIES,
              new Path(entry.getValue().substring(0, entry.getValue().lastIndexOf(".*"))), plan.getLevel());
          result.put(plan1, route(entry.getKey(), 0));
        }
      }
    }
    return result;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(DataAuthPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    //TODO
    //why this plan has not Path field?
    return null;
  }



  default PartitionGroup routePlan(ShowChildPathsPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException  {
    try {
      return route(MManager.getInstance().getStorageGroupNameByPath(plan.getPath().getFullPath()), 0);
    } catch (StorageGroupNotSetException e) {
      //the path is too short to have no a storage group name, e.g., "root"
      //so we can do it locally.
      return getLocalGroups().get(0);
    }
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(ShowDevicesPlan plan)
      throws IllegalPathException {
    Map<String, String> sgPathMap = MManager.getInstance().determineStorageGroup("path");
    Map<PhysicalPlan, PartitionGroup> result =new HashMap<>();
    for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
      result.put(new ShowDevicesPlan(plan.getShowContentType(), new Path(entry.getValue())), route(entry.getKey(), 0));
    }
    return result;
  }

  default Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(ShowTimeSeriesPlan plan)
      throws StorageGroupNotSetException, IllegalPathException {
    //show timeseries is quite special because it has the behavior of wildcard at the tail of the path
    // even though there is no wildcard
    Map<String, String> sgPathMap = MManager.getInstance().determineStorageGroup(plan.getPath().getFullPath()+".*");
    if (sgPathMap.isEmpty()) {
      throw new StorageGroupNotSetException(plan.getPath().getFullPath());
    }
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
      for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
        ShowTimeSeriesPlan newShow = new ShowTimeSeriesPlan(ShowContentType.TIMESERIES, new Path(entry.getValue()));
        result.put(newShow, route(entry.getKey(), 0));
      }
    return result;
  }

  default PartitionGroup routePlan(PropertyPlan plan)
      throws UnsupportedPlanException,StorageGroupNotSetException, IllegalPathException {
    logger.error("PropertyPlan is not implemented");
    throw new UnsupportedPlanException(plan);
  }

  /**
   * @param path can be an incomplete path (but should contain a storage group name)
   *              e.g., if  "root.sg" is a storage group, then path can not be "root".
   * @param timestamp
   * @return
   * @throws StorageGroupNotSetException
   */
  default PartitionGroup partitionByPathTime(String path, long timestamp)
      throws StorageGroupNotSetException {
    String storageGroup = MManager.getInstance().getStorageGroupNameByPath(path);
    return this.route(storageGroup, timestamp);
  }

  /**
   * Get partition info by path and range time
   *
   * @UsedBy NodeTool
   * @return (startTime, endTime) - partitionGroup pair
   */
  default  MultiKeyMap<Long, PartitionGroup> partitionByPathRangeTime(String path,
      long startTime, long endTime) throws StorageGroupNotSetException {
    MultiKeyMap<Long, PartitionGroup> timeRangeMapRaftGroup = new MultiKeyMap<>();
    String storageGroup = MManager.getInstance().getStorageGroupNameByPath(path);
    while (startTime <= endTime) {
      long nextTime = (startTime / PARTITION_INTERVAL + 1) * PARTITION_INTERVAL; //FIXME considering the time unit
      timeRangeMapRaftGroup.put(startTime, Math.min(nextTime - 1, endTime),
          this.route(storageGroup, startTime));
      startTime = nextTime;
    }
    return timeRangeMapRaftGroup;
  }
}
