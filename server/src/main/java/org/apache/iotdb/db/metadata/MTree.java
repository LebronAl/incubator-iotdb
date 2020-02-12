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
package org.apache.iotdb.db.metadata;

import static org.apache.iotdb.db.conf.IoTDBConstant.PATH_WILDCARD;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathAlreadyExistException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.metadata.StorageGroupAlreadySetException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.qp.constant.SQLConstant;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

/**
 * The hierarchical struct of the Metadata Tree is implemented in this class.
 */
public class MTree implements Serializable {

  private static final long serialVersionUID = -4200394435237291964L;
  private static final String PATH_SEPARATOR = "\\.";
  private MNode root;

  MTree(String rootName) {
    this.root = new MNode(rootName, null, false);
  }

  /**
   * Add timeseries. It should check whether seriesPath exists.
   *
   * @param path timeseries path
   * @param dataType data type
   * @param encoding encoding
   * @param compressor compressor
   * @param props props
   */
  void addPath(String path, TSDataType dataType, TSEncoding encoding,
      CompressionType compressor, Map<String, String> props) throws MetadataException {
    String[] nodeNames = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodeNames.length <= 1 || !nodeNames[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }
    MNode cur = getParent(nodeNames);
    String levelPath = cur.getStorageGroupName();

    MNode leaf = new MNode(nodeNames[nodeNames.length - 1], cur, dataType, encoding, compressor);
    if (props != null && !props.isEmpty()) {
      leaf.getSchema().setProps(props);
    }
    leaf.setStorageGroupName(levelPath);
    if (cur.isLeaf()) {
      throw new PathAlreadyExistException(cur.getFullPath());
    }
    cur.addChild(nodeNames[nodeNames.length - 1], leaf);
  }

  /**
   * Add deviceId
   */
  MNode addDeviceId(String deviceId) throws MetadataException {
    String[] nodeNames = MetaUtils.getNodeNames(deviceId, PATH_SEPARATOR);
    if (nodeNames.length <= 1 || !nodeNames[0].equals(root.getName())) {
      throw new IllegalPathException(deviceId);
    }
    MNode cur = root;
    for (int i = 1; i < nodeNames.length; i++) {
      if (!cur.hasChildWithKey(nodeNames[i])) {
        cur.addChild(nodeNames[i], new MNode(nodeNames[i], cur, false));
      }
      cur = cur.getChild(nodeNames[i]);
    }
    return cur;
  }

  /**
   * get the parent of nodes
   *
   * @param nodeNames node names
   */
  private MNode getParent(String[] nodeNames) throws MetadataException {
    MNode cur = root;
    String storageGroupName = null;
    int i = 1;
    while (i < nodeNames.length - 1) {
      String nodeName = nodeNames[i];
      if (cur.isStorageGroup()) {
        storageGroupName = cur.getStorageGroupName();
      }
      if (!cur.hasChildWithKey(nodeName)) {
        if (cur.isLeaf()) {
          throw new PathAlreadyExistException(cur.getFullPath());
        }
        cur.addChild(nodeName, new MNode(nodeName, cur, false));
      }
      cur.setStorageGroupName(storageGroupName);
      cur = cur.getChild(nodeName);
      if (storageGroupName == null) {
        storageGroupName = cur.getStorageGroupName();
      }
      i++;
    }
    cur.setStorageGroupName(storageGroupName);
    return cur;
  }

  /**
   * check whether the given path exists
   *
   * @param path not necessarily the whole seriesPath (possibly a prefix of a sequence)
   */
  boolean isPathExist(String path) {
    String[] nodeNames = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    int i = 0;
    while (i < nodeNames.length - 1) {
      String nodeName = nodeNames[i];
      if (cur.getName().equals(nodeName)) {
        i++;
        nodeName = nodeNames[i];
        if (cur.hasChildWithKey(nodeName)) {
          cur = cur.getChild(nodeName);
        } else {
          return false;
        }
      } else {
        return false;
      }
    }
    return cur.getName().equals(nodeNames[i]);
  }

  /**
   * check whether the given path exists under the given MNode
   */
  boolean isPathExist(MNode node, String path) {
    String[] nodeNames = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodeNames.length < 1) {
      return true;
    }
    if (!node.hasChildWithKey(nodeNames[0])) {
      return false;
    }
    MNode cur = node.getChild(nodeNames[0]);

    int i = 0;
    while (i < nodeNames.length - 1) {
      String nodeName = nodeNames[i];
      if (cur.getName().equals(nodeName)) {
        i++;
        nodeName = nodeNames[i];
        if (cur.hasChildWithKey(nodeName)) {
          cur = cur.getChild(nodeName);
        } else {
          return false;
        }
      } else {
        return false;
      }
    }
    return cur.getName().equals(nodeNames[i]);
  }

  /**
   * set storage group
   *
   * @param path make sure check seriesPath before setting storage group.
   */
  public void setStorageGroup(String path) throws MetadataException {
    String[] nodeNames = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    if (nodeNames.length <= 1 || !nodeNames[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }
    int i = 1;
    while (i < nodeNames.length - 1) {
      MNode temp = cur.getChild(nodeNames[i]);
      if (temp == null) {
        // add one child node
        cur.addChild(nodeNames[i], new MNode(nodeNames[i], cur, false));
      } else if (temp.isStorageGroup()) {
        // before set storage group should check the seriesPath exist or not throw exception
        throw new StorageGroupAlreadySetException(temp.getFullPath());
      }
      cur = cur.getChild(nodeNames[i]);
      i++;
    }
    MNode temp = cur.getChild(nodeNames[i]);
    if (temp == null) {
      cur.addChild(nodeNames[i], new MNode(nodeNames[i], cur, false));
    } else {
      throw new PathAlreadyExistException(temp.getFullPath());
    }
    cur = cur.getChild(nodeNames[i]);
    cur.setDataTTL(IoTDBDescriptor.getInstance().getConfig().getDefaultTTL());
    cur.setStorageGroup(true);

    setStorageGroup(path, cur);
  }

  void deleteStorageGroup(String path) throws MetadataException {
    MNode cur = getNode(path);
    if (!cur.isStorageGroup()) {
      throw new StorageGroupNotSetException(path);
    }
    cur.getParent().deleteChild(cur.getName());
    cur = cur.getParent();
    while (cur != null && !MetadataConstant.ROOT.equals(cur.getName())
        && cur.getChildren().size() == 0) {
      cur.getParent().deleteChild(cur.getName());
      cur = cur.getParent();
    }
  }

  /**
   * Check whether the input path is storage group or not
   *
   * @param path input path
   * @return if the whole path is a storage group, return true. Else return false
   * @apiNote :for cluster
   */
  boolean checkStorageGroup(String path) {
    String[] nodeNames = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    if (nodeNames.length <= 1 || !nodeNames[0].equals(root.getName())) {
      return false;
    }
    int i = 1;
    while (i < nodeNames.length - 1) {
      MNode temp = cur.getChild(nodeNames[i]);
      if (temp == null || temp.isStorageGroup()) {
        return false;
      }
      cur = cur.getChild(nodeNames[i]);
      i++;
    }
    MNode temp = cur.getChild(nodeNames[i]);
    return temp != null && temp.isStorageGroup();
  }

  private void setStorageGroup(String path, MNode node) {
    node.setStorageGroupName(path);
    if (node.getChildren() == null) {
      return;
    }
    for (MNode child : node.getChildren().values()) {
      setStorageGroup(path, child);
    }
  }

  /**
   * Delete one seriesPath from current Metadata Tree.
   *
   * @param path Format: root.node.(node)* Notice: Path must be a complete Path from root to leaf
   * node.
   */
  String deletePath(String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length == 0 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }

    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);
    }

    // if the storage group node is deleted, the dataFileName should be return
    String dataFileName = null;
    if (cur.isStorageGroup()) {
      dataFileName = cur.getStorageGroupName();
    }
    cur.getParent().deleteChild(cur.getName());
    cur = cur.getParent();
    while (cur != null && !MetadataConstant.ROOT.equals(cur.getName())
        && cur.getChildren().size() == 0) {
      if (cur.isStorageGroup()) {
        dataFileName = cur.getStorageGroupName();
        return dataFileName;
      }
      cur.getParent().deleteChild(cur.getName());
      cur = cur.getParent();
    }

    return dataFileName;
  }

  /**
   * Get ColumnSchema for given seriesPath. Notice: Path must be a complete Path from root to leaf
   * node.
   */
  MeasurementSchema getSchemaForOnePath(String path) throws MetadataException {
    MNode leaf = getLeafByPath(path);
    return leaf.getSchema();
  }

  MeasurementSchema getSchemaForOnePath(MNode node, String path) throws MetadataException {
    MNode leaf = getLeafByPath(node, path);
    return leaf.getSchema();
  }

  MeasurementSchema getSchemaForOnePathWithCheck(MNode node, String path)
      throws MetadataException {
    MNode leaf = getLeafByPathWithCheck(node, path);
    return leaf.getSchema();
  }

  MeasurementSchema getSchemaForOnePathWithCheck(String path) throws MetadataException {
    MNode leaf = getLeafByPathWithCheck(path);
    return leaf.getSchema();
  }

  private MNode getLeafByPath(String path) throws MetadataException {
    getNode(path);
    String[] node = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    for (int i = 1; i < node.length; i++) {
      cur = cur.getChild(node[i]);
    }
    if (!cur.isLeaf()) {
      throw new PathNotExistException(path);
    }
    return cur;
  }

  private MNode getLeafByPath(MNode node, String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = node.getChild(nodes[0]);
    for (int i = 1; i < nodes.length; i++) {
      cur = cur.getChild(nodes[i]);
    }
    if (!cur.isLeaf()) {
      throw new PathNotExistException(path);
    }
    return cur;
  }

  private MNode getLeafByPathWithCheck(MNode node, String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length < 1 || !node.hasChildWithKey(nodes[0])) {
      throw new IllegalPathException(path);
    }

    MNode cur = node.getChild(nodes[0]);
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);
    }
    if (!cur.isLeaf()) {
      throw new PathNotExistException(path);
    }
    return cur;
  }

  private MNode getLeafByPathWithCheck(String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length < 2 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }

    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);
    }
    if (!cur.isLeaf()) {
      throw new PathNotExistException(path);
    }
    return cur;
  }

  /**
   * function for getting node by path with file level check.
   */
  MNode getNodeByPathWithStorageGroupCheck(String path)
      throws MetadataException {
    boolean storageGroupChecked = false;
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length < 2 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }

    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        if (!storageGroupChecked) {
          throw new StorageGroupNotSetException(path);
        }
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);

      if (cur.isStorageGroup()) {
        storageGroupChecked = true;
      }
    }

    if (!storageGroupChecked) {
      throw new StorageGroupNotSetException(path);
    }
    return cur;
  }

  /**
   * find and return a seriesPath specified by the path
   *
   * @return last node in given seriesPath
   */
  MNode getNode(String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length < 2 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);
    }
    return cur;
  }

  /**
   * Get the storage group seriesPath from the seriesPath.
   *
   * @return String storage group seriesPath
   */
  String getStorageGroupNameByPath(String path) throws StorageGroupNotSetException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (cur == null) {
        throw new StorageGroupNotSetException(path);
      } else if (cur.isStorageGroup()) {
        return cur.getStorageGroupName();
      } else {
        cur = cur.getChild(nodes[i]);
      }
    }
    if (cur != null && cur.isStorageGroup()) {
      return cur.getStorageGroupName();
    }
    throw new StorageGroupNotSetException(path);
  }

  /**
   * Get all the storage group seriesPaths for one seriesPath.
   *
   * @return List storage group seriesPath list
   * @apiNote :for cluster
   */
  List<String> getAllFileNamesByPath(String pathReg) throws MetadataException {
    ArrayList<String> fileNames = new ArrayList<>();
    String[] nodes = MetaUtils.getNodeNames(pathReg, PATH_SEPARATOR);
    if (nodes.length == 0 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(pathReg);
    }
    findFileName(root, nodes, 1, "", fileNames);
    return fileNames;
  }

  /**
   * Recursively find all fileName according to a specific path
   *
   * @apiNote :for cluster
   */
  private void findFileName(MNode node, String[] nodes, int idx, String parent,
      ArrayList<String> paths) {
    if (node.isStorageGroup()) {
      paths.add(node.getStorageGroupName());
      return;
    }
    String nodeReg;
    if (idx >= nodes.length) {
      nodeReg = PATH_WILDCARD;
    } else {
      nodeReg = nodes[idx];
    }

    if (!(PATH_WILDCARD).equals(nodeReg)) {
      if (node.hasChildWithKey(nodeReg)) {
        findFileName(node.getChild(nodeReg), nodes, idx + 1, parent + node.getName() + ".", paths);
      }
    } else {
      for (MNode child : node.getChildren().values()) {
        findFileName(child, nodes, idx + 1, parent + node.getName() + ".", paths);
      }
    }
  }

  /**
   * function for getting file name by path.
   */
  String getStorageGroupNameByPath(MNode node, String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = node.getChild(nodes[0]);
    for (int i = 1; i < nodes.length; i++) {
      if (cur == null) {
        throw new StorageGroupNotSetException(path);
      } else if (cur.isStorageGroup()) {
        return cur.getStorageGroupName();
      } else {
        cur = cur.getChild(nodes[i]);
      }
    }
    if (cur.isStorageGroup()) {
      return cur.getStorageGroupName();
    }
    throw new StorageGroupNotSetException(path);
  }

  /**
   * Check the prefix of this seriesPath is storage group seriesPath.
   *
   * @return true the prefix of this seriesPath is storage group seriesPath false the prefix of this
   * seriesPath is not storage group seriesPath
   */
  boolean checkFileNameByPath(String path) {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    for (int i = 1; i <= nodes.length; i++) {
      if (cur == null) {
        return false;
      } else if (cur.isStorageGroup()) {
        return true;
      } else {
        cur = cur.getChild(nodes[i]);
      }
    }
    return false;
  }

  /**
   * Get all paths for given seriesPath regular expression Regular expression in this method is
   * formed by the amalgamation of seriesPath and the character '*'.
   *
   * @return A HashMap whose Keys are separated by the storage file name.
   */
  HashMap<String, List<String>> getAllPath(String pathReg) throws MetadataException {
    HashMap<String, List<String>> paths = new HashMap<>();
    String[] nodes = MetaUtils.getNodeNames(pathReg, PATH_SEPARATOR);
    if (nodes.length == 0 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(pathReg);
    }
    findPath(root, nodes, 1, "", paths);
    return paths;
  }

  /**
   * @return all storage groups' MNodes
   */
  List<MNode> getAllStorageGroupNodes() {
    List<MNode> ret = new ArrayList<>();
    Deque<MNode> nodeStack = new ArrayDeque<>();
    nodeStack.add(root);
    while (!nodeStack.isEmpty()) {
      MNode current = nodeStack.pop();
      if (current.isStorageGroup()) {
        ret.add(current);
      } else if (current.hasChildren()) {
        nodeStack.addAll(current.getChildren().values());
      }
    }
    return ret;
  }

  /**
   * function for getting all timeseries paths under the given seriesPath.
   */
  List<List<String>> getShowTimeseriesPath(String pathReg) throws MetadataException {
    List<List<String>> res = new ArrayList<>();
    String[] nodes = MetaUtils.getNodeNames(pathReg, PATH_SEPARATOR);
    if (nodes.length == 0 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(pathReg);
    }
    findPath(root, nodes, 1, "", res);
    return res;
  }

  /**
   * function for getting leaf node path in the next level of the given path.
   *
   * @return All leaf nodes' seriesPath(s) of given seriesPath.
   */
  List<String> getLeafNodePathInNextLevel(String path) throws MetadataException {
    List<String> ret = new ArrayList<>();
    MNode cur = getNode(path);
    for (MNode child : cur.getChildren().values()) {
      if (child.isLeaf()) {
        ret.add(path + "." + child.getName());
      }
    }
    return ret;
  }

  /**
   * function for getting child node path in the next level of the given path.
   *
   * @return All child nodes' seriesPath(s) of given seriesPath.
   */
  Set<String> getChildNodePathInNextLevel(String path) throws MetadataException {
    Set<String> ret = new HashSet<>();
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (!nodes[0].equals(root.getName())) {
      throw new IllegalPathException(path);
    }
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      if (!cur.hasChildWithKey(nodes[i])) {
        throw new PathNotExistException(path);
      }
      cur = cur.getChild(nodes[i]);
    }
    if (!cur.hasChildren()) {
      throw new PathNotExistException(path);
    }
    for (MNode child : cur.getChildren().values()) {
      ret.add(path + "." + child.getName());
    }
    return ret;
  }

  /**
   * Calculate the count of storage-level nodes included in given seriesPath.
   *
   * @return The total count of storage-level nodes.
   */
  int getFileCountForOneType(String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length != 2 || !nodes[0].equals(root.getName())
        || !root.hasChildWithKey(nodes[1])) {
      throw new MetadataException("Timeseries must be " + root.getName()
          + ". X (X is one of the nodes of root's children)");
    }
    return getFileCountForOneNode(root.getChild(nodes[1]));
  }

  private int getFileCountForOneNode(MNode node) {
    if (node.isStorageGroup()) {
      return 1;
    }
    int sum = 0;
    if (!node.isLeaf()) {
      for (MNode child : node.getChildren().values()) {
        sum += getFileCountForOneNode(child);
      }
    }
    return sum;
  }

  /**
   * Get all device type in current Metadata Tree.
   *
   * @return a list contains all distinct device type
   */
  ArrayList<String> getAllType() {
    ArrayList<String> res = new ArrayList<>();
    if (root != null) {
      res.addAll(root.getChildren().keySet());
    }
    return res;
  }

  /**
   * Get all storage groups in current Metadata Tree.
   *
   * @return a list contains all distinct storage groups
   */
  List<String> getAllStorageGroupList() {
    List<String> res = new ArrayList<>();
    if (root != null) {
      findStorageGroup(root, "root", res);
    }
    return res;
  }

  private void findStorageGroup(MNode node, String path, List<String> res) {
    if (node.isStorageGroup()) {
      res.add(path);
      return;
    }
    for (MNode childNode : node.getChildren().values()) {
      findStorageGroup(childNode, path + "." + childNode.toString(), res);
    }
  }

  /**
   * Get all devices in current Metadata Tree.
   *
   * @return a list contains all distinct device names
   */
  List<String> getAllDevices() throws MetadataException {
    return getDevices(SQLConstant.ROOT);
  }

  /**
   * Get all devices in current Metadata Tree with prefixPath.
   *
   * @return a list contains all distinct devices names
   */
  List<String> getDevices(String prefixPath) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(prefixPath, PATH_SEPARATOR);
    if (nodes.length == 0 || !nodes[0].equals(root.getName())) {
      throw new IllegalPathException(prefixPath);
    }
    List<String> devices = new ArrayList<>();
    findDevices(root, nodes, 1, "", devices);
    return devices;
  }

  /**
   * Traverse the MTree to match all devices with prefix path.
   *
   * @param node the current traversing node
   * @param nodes split the prefix path with '.'
   * @param idx the current index of array nodes
   * @param parent store the node string having traversed
   * @param res store all matched device names
   */
  private void findDevices(MNode node, String[] nodes, int idx, String parent, List<String> res) {
    String nodeReg;
    if (idx >= nodes.length) {
      nodeReg = PATH_WILDCARD;
    } else {
      nodeReg = nodes[idx];
    }
    if (!(PATH_WILDCARD).equals(nodeReg)) {
      if (node.hasChildWithKey(nodeReg)) {
        if (node.getChild(nodeReg).isLeaf()) {
          res.add(parent + node.getName());
        } else {
          findDevices(node.getChild(nodeReg), nodes, idx + 1, parent + node.getName() + ".", res);
        }
      }
    } else {
      boolean deviceAdded = false;
      for (MNode child : node.getChildren().values()) {
        if (child.isLeaf() && !deviceAdded) {
          res.add(parent + node.getName());
          deviceAdded = true;
        } else if (!child.isLeaf()) {
          findDevices(child, nodes, idx + 1, parent + node.getName() + ".", res);
        }
      }
    }
  }

  /**
   * Get all nodes at the given level in current Metadata Tree.
   *
   * @return a list contains all nodes at the given level
   */
  List<String> getNodesList(String schemaPattern, int nodeLevel) throws SQLException {
    List<String> res = new ArrayList<>();
    String[] nodes = MetaUtils.getNodeNames(schemaPattern, PATH_SEPARATOR);
    MNode node;
    if ((node = root) != null) {
      if (nodes[0].equals("root")) {
        for (int i = 1; i < nodes.length; i++) {
          if (node.getChild(nodes[i]) != null) {
            node = node.getChild(nodes[i]);
          } else {
            throw new SQLException(nodes[i - 1] + " does not have the child node " + nodes[i]);
          }
        }
        findNodes(node, schemaPattern, res, nodeLevel - (nodes.length - 1));
      } else {
        throw new SQLException("Incorrect root node " + nodes[0] + " selected");
      }
    }
    return res;
  }

  private void findNodes(MNode node, String path, List<String> res, int targetLevel) {
    if (node == null) {
      return;
    }
    if (targetLevel == 0) {
      res.add(path);
      return;
    }
    if (node.hasChildren()) {
      for (MNode child : node.getChildren().values()) {
        findNodes(child, path + "." + child.toString(), res, targetLevel - 1);
      }
    }
  }

  /**
   * Get all delta objects for given type.
   *
   * @param type device Type
   * @return a list contains all delta objects for given type
   */
  ArrayList<String> getDeviceForOneType(String type) throws MetadataException {
    String path = root.getName() + "." + type;
    getNode(path);
    HashMap<String, Integer> deviceMap = new HashMap<>();
    MNode typeNode = root.getChild(type);
    putDeviceToMap(root.getName(), typeNode, deviceMap);
    return new ArrayList<>(deviceMap.keySet());
  }

  private void putDeviceToMap(String path, MNode node, HashMap<String, Integer> deviceMap) {
    if (node.isLeaf()) {
      deviceMap.put(path, 1);
    } else {
      for (String child : node.getChildren().keySet()) {
        String newPath = path + "." + node.getName();
        putDeviceToMap(newPath, node.getChildren().get(child), deviceMap);
      }
    }
  }

  /**
   * Get all ColumnSchemas for given delta object type.
   *
   * @param path A seriesPath represented one Delta object
   * @return a list contains all column schema
   */
  ArrayList<MeasurementSchema> getSchemaForOneType(String path) throws MetadataException {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    if (nodes.length != 2 || !nodes[0].equals(root.getName())
        || !root.hasChildWithKey(nodes[1])) {
      throw new MetadataException("Timeseries must be " + root.getName()
          + ". X (X is one of the nodes of root's children)");
    }
    HashMap<String, MeasurementSchema> leafMap = new HashMap<>();
    putLeafToLeafMap(root.getChild(nodes[1]), leafMap);
    return new ArrayList<>(leafMap.values());
  }

  /**
   * Get all ColumnSchemas for the storage group seriesPath.
   *
   * @return ArrayList<ColumnSchema> The list of the schema
   */
  ArrayList<MeasurementSchema> getSchemaForOneStorageGroup(String path) {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    HashMap<String, MeasurementSchema> leafMap = new HashMap<>();
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      cur = cur.getChild(nodes[i]);
    }
    // cur is the storage group node
    putLeafToLeafMap(cur, leafMap);
    return new ArrayList<>(leafMap.values());
  }

  /**
   * function for getting schema map for one storage group.
   */
  Map<String, MeasurementSchema> getSchemaMapForOneStorageGroup(String path) {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      cur = cur.getChild(nodes[i]);
    }
    return cur.getSchemaMap();
  }

  /**
   * function for getting num schema map for one file node.
   */
  Map<String, Integer> getNumSchemaMapForOneFileNode(String path) {
    String[] nodes = MetaUtils.getNodeNames(path, PATH_SEPARATOR);
    MNode cur = root;
    for (int i = 1; i < nodes.length; i++) {
      cur = cur.getChild(nodes[i]);
    }
    return cur.getNumSchemaMap();
  }

  private void putLeafToLeafMap(MNode node, HashMap<String, MeasurementSchema> leafMap) {
    if (node.isLeaf()) {
      if (!leafMap.containsKey(node.getName())) {
        leafMap.put(node.getName(), node.getSchema());
      }
      return;
    }
    for (MNode child : node.getChildren().values()) {
      putLeafToLeafMap(child, leafMap);
    }
  }

  private void findPath(MNode node, String[] nodes, int idx, String parent,
      HashMap<String, List<String>> paths) {
    if (node.isLeaf()) {
      if (nodes.length <= idx) {
        String fileName = node.getStorageGroupName();
        String nodeName;
        if (node.getName().contains(TsFileConstant.PATH_SEPARATOR)) {
          nodeName = "\"" + node + "\"";
        } else {
          nodeName = node.toString();
        }
        String nodePath = parent + nodeName;
        List<String> pathList = paths.computeIfAbsent(fileName, key -> new ArrayList<>());
        pathList.add(nodePath);
      }
      return;
    }
    String nodeReg;
    if (idx >= nodes.length) {
      nodeReg = PATH_WILDCARD;
    } else {
      nodeReg = nodes[idx];
    }

    if (!(PATH_WILDCARD).equals(nodeReg)) {
      if (node.hasChildWithKey(nodeReg)) {
        findPath(node.getChild(nodeReg), nodes, idx + 1, parent + node.getName() + ".", paths);
      }
    } else {
      for (MNode child : node.getChildren().values()) {
        findPath(child, nodes, idx + 1, parent + node.getName() + ".", paths);
      }
    }
  }

  /*
   * Iterate through MTree to fetch metadata info of all leaf nodes under the given seriesPath
   */
  private void findPath(MNode node, String[] nodes, int idx, String parent,
      List<List<String>> res) {
    if (node.isLeaf()) {
      if (nodes.length <= idx) {
        String nodePath = parent + node;
        List<String> tsRow = new ArrayList<>(5);// get [name,storage group,resultDataType,encoding]
        tsRow.add(nodePath);
        MeasurementSchema measurementSchema = node.getSchema();
        tsRow.add(node.getStorageGroupName());
        tsRow.add(measurementSchema.getType().toString());
        tsRow.add(measurementSchema.getEncodingType().toString());
        tsRow.add(measurementSchema.getCompressor().toString());
        res.add(tsRow);
      }
      return;
    }
    String nodeReg;
    if (idx >= nodes.length) {
      nodeReg = PATH_WILDCARD;
    } else {
      nodeReg = nodes[idx];
    }

    if (!(PATH_WILDCARD).equals(nodeReg)) {
      if (node.hasChildWithKey(nodeReg)) {
        findPath(node.getChild(nodeReg), nodes, idx + 1, parent + node.getName() + ".", res);
      }
    } else {
      for (MNode child : node.getChildren().values()) {
        findPath(child, nodes, idx + 1, parent + node.getName() + ".", res);
      }
    }
  }

  @Override
  public String toString() {
    return jsonToString(toJson());
  }

  private static String jsonToString(JSONObject jsonObject) {
    return JSON.toJSONString(jsonObject, SerializerFeature.PrettyFormat);
  }

  private JSONObject toJson() {
    JSONObject jsonObject = new JSONObject();
    jsonObject.put(root.getName(), mNodeToJSON(root));
    return jsonObject;
  }

  private JSONObject mNodeToJSON(MNode node) {
    JSONObject jsonObject = new JSONObject();
    if (!node.isLeaf() && node.getChildren().size() > 0) {
      for (MNode child : node.getChildren().values()) {
        jsonObject.put(child.getName(), mNodeToJSON(child));
      }
    } else if (node.isLeaf()) {
      jsonObject.put("DataType", node.getSchema().getType());
      jsonObject.put("Encoding", node.getSchema().getEncodingType());
      jsonObject.put("Compressor", node.getSchema().getCompressor());
      jsonObject.put("args", node.getSchema().getProps().toString());
      jsonObject.put("StorageGroup", node.getStorageGroupName());
    }
    return jsonObject;
  }

  public MNode getRoot() {
    return root;
  }

  /**
   * combine multiple metadata in string format
   */
  static String combineMetadataInStrings(String[] metadataStrs) {
    JSONObject[] jsonObjects = new JSONObject[metadataStrs.length];
    for (int i = 0; i < jsonObjects.length; i++) {
      jsonObjects[i] = JSONObject.parseObject(metadataStrs[i]);
    }

    JSONObject root = jsonObjects[0];
    for (int i = 1; i < jsonObjects.length; i++) {
      root = combineJSONObjects(root, jsonObjects[i]);
    }
    return jsonToString(root);
  }

  private static JSONObject combineJSONObjects(JSONObject a, JSONObject b) {
    JSONObject res = new JSONObject();

    Set<String> retainSet = new HashSet<>(a.keySet());
    retainSet.retainAll(b.keySet());
    Set<String> aCha = new HashSet<>(a.keySet());
    Set<String> bCha = new HashSet<>(b.keySet());
    aCha.removeAll(retainSet);
    bCha.removeAll(retainSet);
    for (String key : aCha) {
      res.put(key, a.getJSONObject(key));
    }
    for (String key : bCha) {
      res.put(key, b.get(key));
    }
    for (String key : retainSet) {
      Object v1 = a.get(key);
      Object v2 = b.get(key);
      if (v1 instanceof JSONObject && v2 instanceof JSONObject) {
        res.put(key, combineJSONObjects((JSONObject) v1, (JSONObject) v2));
      } else {
        res.put(key, v1);
      }
    }
    return res;
  }
}
