package com.bonree.brfs.schedulers.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bonree.brfs.common.ReturnCode;
import com.bonree.brfs.common.ZookeeperPaths;
import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.task.TaskState;
import com.bonree.brfs.common.task.TaskType;
import com.bonree.brfs.common.utils.BrStringUtils;
import com.bonree.brfs.common.utils.TimeUtils;
import com.bonree.brfs.configuration.Configs;
import com.bonree.brfs.configuration.units.CommonConfigs;
import com.bonree.brfs.duplication.storagename.StorageNameManager;
import com.bonree.brfs.duplication.storagename.StorageNameNode;
import com.bonree.brfs.schedulers.ManagerContralFactory;
import com.bonree.brfs.schedulers.jobs.biz.UserDeleteJob;
import com.bonree.brfs.schedulers.jobs.system.CopyCheckJob;
import com.bonree.brfs.schedulers.jobs.system.CopyCountCheck;
import com.bonree.brfs.schedulers.jobs.system.CreateSystemTask;
import com.bonree.brfs.schedulers.task.manager.MetaTaskManagerInterface;
import com.bonree.brfs.schedulers.task.manager.impl.DefaultReleaseTask;
import com.bonree.brfs.schedulers.task.model.AtomTaskModel;
import com.bonree.brfs.schedulers.task.model.AtomTaskResultModel;
import com.bonree.brfs.schedulers.task.model.TaskModel;
import com.bonree.brfs.schedulers.task.model.TaskResultModel;
import com.bonree.brfs.schedulers.task.model.TaskServerNodeModel;
import com.bonree.brfs.schedulers.task.model.TaskTypeModel;

public class TasksUtils {
	/**
	 * 概述：
	 * @param services
	 * @param serverConfig
	 * @param zkPaths
	 * @param sn
	 * @param startTime
	 * @param endTime
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	 public static ReturnCode createUserDeleteTask(List<Service> services, ZookeeperPaths zkPaths, StorageNameNode sn, long startTime, long endTime){
		 	if(services == null || services.isEmpty()){
		 		return ReturnCode.DELETE_DATA_ERROR;
		 	}
		 	if(sn == null){
		 		return ReturnCode.STORAGE_NONEXIST_ERROR;
		 	}
		 	
		 	String zkAddresses = Configs.getConfiguration().GetConfig(CommonConfigs.CONFIG_ZOOKEEPER_ADDRESSES);
		 	ReturnCode code = checkTime(startTime, endTime, sn.getCreateTime(), 3600000);
		 	MetaTaskManagerInterface release = DefaultReleaseTask.getInstance();
		 	release.setPropreties(zkAddresses, zkPaths.getBaseTaskPath(), zkPaths.getBaseLocksPath());
	    	TaskTypeModel tmodel = release.getTaskTypeInfo(TaskType.USER_DELETE.name());
	    	if(!tmodel.isSwitchFlag()) {
	    		return ReturnCode.FORBID_DELETE_DATA_ERROR;
	    	}
	    	TaskModel task = TasksUtils.createUserDelete(sn, TaskType.USER_DELETE, "", startTime, endTime);
	    	if(task == null){
	    		return ReturnCode.DELETE_DATA_ERROR;
	    	}
	    	List<String> serverIds = CreateSystemTask.getServerIds(services);
	        String taskName = CreateSystemTask.updateTask(release, task, serverIds, TaskType.USER_DELETE);
	        if(!BrStringUtils.isEmpty(taskName)) {
	        	return ReturnCode.SUCCESS;
	        }else {
	        	return ReturnCode.DELETE_DATA_ERROR;
	        }
	    }
	/**
	 * 概述：创建可执行任务信息
	 * @param sn storageName信息
	 * @param taskType 任务类型
	 * @param opertationContent 执行的内容，暂时无用
	 * @param startTime 要操作数据的开始时间 -1标识为sn的创建时间
	 * @param endTime 要操作数据的结束时间。
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static TaskModel createUserDelete(final StorageNameNode sn,final TaskType taskType, final String opertationContent, final long startTime, final long endTime){
		if(sn == null ||taskType == null){
			return null;
		}
		TaskModel task = new TaskModel();
		List<AtomTaskModel> storageAtoms = new ArrayList<AtomTaskModel>();
		if(endTime == 0 || startTime >= endTime){
			return null;
		}	
		AtomTaskModel atom = null;
		String dirName = null;
		String snName = sn.getName();
		int count = sn.getReplicateCount();
		long startHour = 0;
		long endHour =  endTime/1000/60/60*60*60*1000;
		boolean isDeleteSN = false;
		// 删除sn
		if(startTime <=0){
			startHour = sn.getCreateTime()/1000/60/60*60*60*1000;
			isDeleteSN = true;
		}else{
			startHour = startTime/1000/60/60*60*60*1000;
		}
		// 若是删除sn时，创建时间与删除时间间隔较短时，结束时间向后退
		if(startHour == endHour && startTime <=0) {
			endHour = endHour + 3600000;
		}
		if(startHour >= endHour) {
			return null;
		}
		
		for(int i = 1; i<=count; i++){
			atom = new AtomTaskModel();
			atom.setStorageName(snName);
			atom.setTaskOperation(opertationContent);
			atom.setDirName(i+"");
			atom.setDataStartTime(TimeUtils.formatTimeStamp(startHour, TimeUtils.TIME_MILES_FORMATE));
			atom.setDataStopTime(TimeUtils.formatTimeStamp(endHour, TimeUtils.TIME_MILES_FORMATE));
			atom.setTaskOperation(isDeleteSN ? UserDeleteJob.DELETE_SN_ALL:UserDeleteJob.DELETE_PART);
			storageAtoms.add(atom);
		}
		task.setAtomList(storageAtoms);
		task.setTaskState(TaskState.INIT.code());
		task.setCreateTime(TimeUtils.formatTimeStamp(System.currentTimeMillis(), TimeUtils.TIME_MILES_FORMATE));
		task.setTaskType(taskType.code());
		return task;
	}
	
	/***
	 * 概述：检测时间
	 * @param startTime
	 * @param endTime
	 * @param cTime
	 * @param granule
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static ReturnCode checkTime(long startTime, long endTime, long cTime, long granule) {
		// 1，时间格式不对
		if(startTime != (startTime - startTime%granule)
				|| endTime !=(endTime - endTime%granule)) {
			return ReturnCode.TIME_FORMATE_ERROR;
		}
		long currentTime = System.currentTimeMillis();
		long cuGra = currentTime - currentTime%granule;
		long sGra = startTime - startTime%granule;
		long eGra = endTime - endTime%granule;
		long cGra = cTime - cTime%granule;
		// 2.开始时间等于结束世界
		if(sGra >= eGra) {
			return ReturnCode.PARAMETER_ERROR;
		}
		// 3.开始时间，结束时间小于创建时间
		if(cGra >sGra ||cGra >eGra) {
			return ReturnCode.TIME_EARLIER_THAN_CREATE_ERROR;
		}
		// 4.当前时间
		if(cuGra <= sGra || cuGra<eGra) {
			return ReturnCode.FORBID_DELETE_CURRENT_ERROR;
		}
		// 若成功则返回null
		return ReturnCode.SUCCESS;
	}
	public static void createCopyTask(String taskName) {
		ManagerContralFactory mcf = ManagerContralFactory.getInstance();
		MetaTaskManagerInterface release = mcf.getTm();
		StorageNameManager snm = mcf.getSnm();
		List<StorageNameNode> snList = snm.getStorageNameNodeList();
		if(snList == null || snList.isEmpty()) {
			return ;
		}
		List<String> sNames = release.getTaskServerList(TaskType.SYSTEM_CHECK.name(), taskName);
		if(sNames == null|| sNames.isEmpty()) {
			return ;
		}
		List<TaskServerNodeModel> sTasks = new ArrayList<TaskServerNodeModel>();
		Map<String,Integer> copyMap = getReplicationMap(snList);
		TaskServerNodeModel sTask = null;
		for(String sName : sNames) {
			sTask = release.getTaskServerContentNodeInfo(TaskType.SYSTEM_CHECK.name(), taskName, sName);
			if(sTask == null) {
				continue;
			}
			sTasks.add(sTask);
		}
		if(sTasks == null || sTasks.isEmpty()) {
			return ;
		}
		TaskModel task = getErrorFile(sTasks, copyMap);
		String tName = release.updateTaskContentNode(task, TaskType.SYSTEM_COPY_CHECK.name(), null);
		if(BrStringUtils.isEmpty(tName)) {
			return;
		}
		for(String sname :sNames) {
			release.updateServerTaskContentNode(sname, tName, TaskType.SYSTEM_COPY_CHECK.name(), TaskServerNodeModel.getInitInstance());
		}
	}
	private static Map<String,Integer> getReplicationMap(List<StorageNameNode> snList){
		if(snList == null || snList.isEmpty()) {
			return null;
		}
		Map<String,Integer> map = new HashMap<String,Integer>();
		String snName = null;
		int replicationCount = 0;
		for(StorageNameNode sn : snList) {
			snName = sn.getName();
			replicationCount = sn.getReplicateCount();
			map.put(snName, replicationCount);
		}
		return map;
	}
	/**
	 * 概述：生成任务信息
	 * @param taskContents
	 * @param snMap
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static TaskModel getErrorFile(List<TaskServerNodeModel> taskContents, Map<String,Integer> snMap){
		if(taskContents == null || taskContents.isEmpty()) {
			return null;
		}
		List<AtomTaskResultModel> atomRs = new ArrayList<AtomTaskResultModel>();
		TaskResultModel tmpR = null;
		List<AtomTaskResultModel> tmpRs = null;
		Map<String,Map<String,AtomTaskModel>> rmap = new HashMap<String,Map<String,AtomTaskModel>>();
		Map<String,AtomTaskModel> emap = null;
		String snName = null;
		String key = null;
		AtomTaskModel atom = null;
		for(TaskServerNodeModel serverModel : taskContents) {
			tmpR = serverModel.getResult();
			if(tmpR == null) {
				continue;
			}
			tmpRs = tmpR.getAtoms();
			if(tmpRs == null || tmpRs.isEmpty()) {
				continue;
			}
			for(AtomTaskResultModel r : tmpRs) {
				if(r == null) {
					continue;
				}
				snName = r.getSn();
				if(!rmap.containsKey(snName)) {
					rmap.put(snName, new HashMap<String,AtomTaskModel>());
				}
				emap = rmap.get(snName);
				key = r.getDataStartTime() + "_"+r.getDataStopTime();
				if(!emap.containsKey(key)) {
					atom = new AtomTaskModel();
					atom.setDataStartTime(r.getDataStartTime());
					atom.setDataStopTime(r.getDataStopTime());
					atom.setStorageName(snName);
					atom.setTaskOperation(CopyCheckJob.RECOVERY_CRC);
					emap.put(key, atom);
				}
				atom = emap.get(key);
				atom.addAllFiles(r.getFiles());
			}
		}
		List<AtomTaskModel> tList = filterError(rmap, snMap);
		if(tList == null|| tList.isEmpty()) {
			return null;
		}
		TaskModel tTask = new TaskModel();
		tTask.setCreateTime(TimeUtils.formatTimeStamp(System.currentTimeMillis(), TimeUtils.TIME_MILES_FORMATE));
		tTask.setAtomList(tList);
		tTask.setTaskState(TaskState.INIT.code());
		tTask.setTaskType(TaskType.SYSTEM_COPY_CHECK.code());
		tTask.setTaskOperation(CopyCheckJob.RECOVERY_CRC);
		return  tTask;
	}
	
	/**
	 * 概述：收集可执行子任务
	 * @param rmap
	 * @param snCountMap
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<AtomTaskModel> filterError(Map<String,Map<String,AtomTaskModel>> rmap , Map<String,Integer>snCountMap){
		if(rmap == null || rmap.isEmpty() || snCountMap == null || snCountMap.isEmpty()) {
			return null;
		}
		String snName = null;
		Map<String,AtomTaskModel> sMap = null;
		int count = 0;
		List<AtomTaskModel> atoms = new ArrayList<AtomTaskModel>();
		List<AtomTaskModel> tmp = null;
		for(Map.Entry<String, Integer> entry : snCountMap.entrySet()) {
			snName = entry.getKey();
			count = entry.getValue();
			if(count == 1) {
				continue;
			}
			if(!rmap.containsKey(snName)) {
				continue;
			}
			sMap = rmap.get(snName);
			if(sMap == null || sMap.isEmpty()) {
				continue;
			}
			tmp = collectAtoms(sMap, count);
			if(tmp == null || tmp.isEmpty()) {
				continue;
			}
			atoms.addAll(tmp);
		}
		return atoms;
	}
	/**
	 * 概述：收集子任务
	 * @param sMap
	 * @param count
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<AtomTaskModel> collectAtoms(Map<String,AtomTaskModel> sMap, int count){
		if(sMap == null || sMap.isEmpty() || count <=1) {
			return null;
		}
		List<String> eFiles = null;
		List<AtomTaskModel> atoms = new ArrayList<AtomTaskModel>();
		for(AtomTaskModel atom : sMap.values()) {
			eFiles = atom.getFiles();
			if(eFiles == null || eFiles.isEmpty()) {
				continue;
			}
			eFiles = collectFiles(eFiles, count);
			if(eFiles == null|| eFiles.isEmpty()) {
				continue;
			}
			atom.getFiles().clear();
			atom.setFiles(eFiles);
			atoms.add(atom);
		}
		return atoms;
	}
	/**
	 * 概述：收集可恢复的文件
	 * @param files
	 * @param count
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<String> collectFiles(List<String> files, int count){
		if(files == null || files.isEmpty()) {
			return null;
		}
		Map<String,Integer> cMap = new HashMap<String,Integer>();
		for(String file :files) {
			if(cMap.containsKey(file)) {
				cMap.put(file, cMap.get(file) +1);
			}else {
				cMap.put(file, 1);
			}
		}
		if(cMap == null || cMap.isEmpty()) {
			return null;
		}
		return collectionFiles(cMap, count);
	}
	/**
	 * 概述：收集可恢复的文件
	 * @param cmap
	 * @param replicationCount
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<String> collectionFiles(Map<String,Integer> cmap, int replicationCount){
		if(cmap == null || cmap.isEmpty()) {
			return null;
		}
		String fileName = null;
		int count = 0;
		List<String> files = new ArrayList<String>();
		for(Map.Entry<String, Integer> entry : cmap.entrySet()) {
			fileName = entry.getKey();
			count = entry.getValue();
			if(count >= replicationCount) {
				continue;
			}
			files.add(fileName);
		}
		return files;
	}
}
