
package com.bonree.brfs.schedulers.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.service.ServiceManager;
import com.bonree.brfs.common.utils.BrStringUtils;
import com.bonree.brfs.common.utils.ByteUtils;
import com.bonree.brfs.common.utils.FileUtils;
import com.bonree.brfs.common.utils.JsonUtils;
import com.bonree.brfs.common.utils.TimeUtils;
import com.bonree.brfs.common.write.data.FSCode;
import com.bonree.brfs.common.zookeeper.curator.CuratorClient;
import com.bonree.brfs.configuration.Configs;
import com.bonree.brfs.configuration.units.CommonConfigs;
import com.bonree.brfs.disknode.client.DiskNodeClient;
import com.bonree.brfs.disknode.client.LocalDiskNodeClient;
import com.bonree.brfs.disknode.client.TcpDiskNodeClient;
import com.bonree.brfs.disknode.fileformat.impl.SimpleFileHeader;
import com.bonree.brfs.duplication.storageregion.StorageRegion;
import com.bonree.brfs.duplication.storageregion.StorageRegionManager;
import com.bonree.brfs.rebalance.route.SecondIDParser;
import com.bonree.brfs.schedulers.ManagerContralFactory;
import com.bonree.brfs.schedulers.jobs.system.CopyCheckJob;
import com.bonree.brfs.schedulers.task.model.AtomTaskModel;
import com.bonree.brfs.schedulers.task.model.AtomTaskResultModel;
import com.bonree.brfs.schedulers.task.model.BatchAtomModel;
import com.bonree.brfs.schedulers.task.model.TaskResultModel;
import com.bonree.brfs.server.identification.ServerIDManager;

public class CopyRecovery {
	private static final Logger LOG = LoggerFactory.getLogger(CopyRecovery.class);
	/**
	 * 概述：修复目录
	 * @param content
	 * @param zkHosts
	 * @param baseRoutesPath
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static TaskResultModel recoveryDirs(String content, String zkHosts, String baseRoutesPath,String dataPath) {
		TaskResultModel result = new TaskResultModel();
		BatchAtomModel batch = converStringToBatch(content);
		if(batch == null){
			result.setSuccess(false);
			LOG.warn("<recoveryDirs> batch is empty");
			return result;
		}
		List<AtomTaskModel> atoms = batch.getAtoms();
		if(atoms == null|| atoms.isEmpty()){
			result.setSuccess(true);
			LOG.warn("<recoveryDirs> file is empty");
			return result;
		}
		ManagerContralFactory mcf = ManagerContralFactory.getInstance();
		ServerIDManager sim = mcf.getSim();
		ServiceManager sm = mcf.getSm();
		Service localServer = sm.getServiceById(mcf.getGroupName(), mcf.getServerId());
		StorageRegionManager snm = mcf.getSnm();
		
		DiskNodeClient client = new LocalDiskNodeClient();
		CuratorClient curatorClient = CuratorClient.getClientInstance(zkHosts);
		StorageRegion sn = null;
		SecondIDParser parser = null;
		String snName = null;
		int snId = 0;
		String snSId = null;
		AtomTaskResultModel atomR = null;
		List<String> errors = null;
		for (AtomTaskModel atom : atoms) {
			atomR = new AtomTaskResultModel();
			atomR.setFiles(atom.getFiles());
			atomR.setSn(atom.getStorageName());
			snName = atom.getStorageName();
			sn = snm.findStorageRegionByName(snName);
			if (sn == null) {
				atomR.setSuccess(false);
				result.setSuccess(false);
				result.add(atomR);
				LOG.debug("<recoveryDirs> sn == null snName :{}",snName);
				continue;
			}
			snId = sn.getId();
			snSId = sim.getSecondServerID(snId);
			parser = new SecondIDParser(curatorClient, snId, baseRoutesPath);
			parser.updateRoute();
			errors = recoveryFiles(sm, sim, parser, sn, atom,dataPath);
			if(errors == null || errors.isEmpty()){
				result.add(atomR);
				LOG.debug("<recoveryDirs> result is empty snName:{}", snName);
				continue;
			}
			atomR.addAll(errors);
			atomR.setSuccess(false);
			result.setSuccess(false);
		}
		curatorClient.close();
		return result;
	}
	/**
	 * 概述：字符串转Batch
	 * @param content
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static BatchAtomModel converStringToBatch(String content){
		if (BrStringUtils.isEmpty(content)) {
			LOG.warn("content is empty");
			return null;
		}
		BatchAtomModel batch = JsonUtils.toObjectQuietly(content, BatchAtomModel.class);
		if (batch == null) {
			LOG.warn("batch content is empty");
			return null;
		}
		return batch;
	}

	/**
	 * 概述：修复文件
	 * @param sm
	 * @param sim
	 * @param atom
	 * @param parser
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<String> recoveryFiles(ServiceManager sm,ServerIDManager sim, SecondIDParser parser, StorageRegion snNode,AtomTaskModel atom, String dataPath) {

		String snName = atom.getStorageName();
		long start = TimeUtils.getMiles(atom.getDataStartTime(), TimeUtils.TIME_MILES_FORMATE);
		long endTime = TimeUtils.getMiles(atom.getDataStopTime(), TimeUtils.TIME_MILES_FORMATE);
		long granule = endTime -start;
		String dirName = TimeUtils.timeInterval(start, granule);
		List<String> fileNames = atom.getFiles();
		if (fileNames == null || fileNames.isEmpty()) {
			LOG.warn("<recoverFiles> {} files name is empty", snName);
			return null;
		}
		if (snNode == null) {
			LOG.warn("<recoverFiles> {} sn node is empty", snName);
			return null;
		}
		boolean isSuccess = false;
		List<String> errors = new ArrayList<String>();
		for (String fileName : fileNames) {
			isSuccess = recoveryFileByName( sm, sim, parser, snNode, fileName, dirName, dataPath,atom.getTaskOperation());
			if(!isSuccess){
				errors.add(fileName);
			}
		}
		return errors;
	}
	/**
	 * 概述：恢复单个文件
	 * @param sm
	 * @param sim
	 * @param parser
	 * @param snNode
	 * @param fileName
	 * @param dirName
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static boolean recoveryFileByName(ServiceManager sm,ServerIDManager sim, SecondIDParser parser, StorageRegion snNode, String fileName,String dirName, String dataPath,String operation){
		String[] sss = null;
		String remoteName = null;
		Service remoteService = null;
		String path = null;
		String localPath = null;
		int remoteIndex = 0;
		int localIndex = 0;
		String remotePath = null;
		String serverId = sim.getFirstServerID();
		boolean isSuccess = true;
		String snName = snNode.getName();
		int snId = snNode.getId();
		sss = parser.getAliveSecondID(fileName);
		if (sss == null) {
			LOG.warn("<recoveryFile> alive second Ids is empty");
			return false;
		}
		String secondId = sim.getSecondServerID(snId);
		if (BrStringUtils.isEmpty(secondId)) {
			LOG.warn("<recoveryFile> {} {} secondid is empty ",snName, snId);
			return false;
		}
		localIndex = isContain(sss, secondId);
		if (-1 == localIndex) {
			LOG.info("<recoveryFile> {} {} {} is not mine !! skip",secondId, snName, fileName );
			return true;
		}
		
		localPath = "/"+snName + "/" + localIndex + "/" + dirName + "/" + fileName;
		String localDir = "/"+snName + "/" + localIndex + "/" + dirName+"/";
		File dir = new File(dataPath + localDir);
		if(!dir.exists()) {
			boolean createFlag = dir.mkdirs();
			LOG.debug("<recoveryFile> create dir :{}, stat:{}",localDir,createFlag);
		}
		if(CopyCheckJob.RECOVERY_CRC.equals(operation)) {
			boolean flag = FileCollection.check(dataPath + localPath);
			LOG.debug("locaPath : {}, CRCSTATUS: {}", dataPath+localPath, flag);
			if(flag) {
				return true;
			}else {
				boolean status = FileUtils.deleteFile(dataPath+localPath);
				LOG.info("{} crc is error!! delete {}", localPath,status);
			}
		}else {
			File file = new File(dataPath + localPath);
			if(file.exists()){
				LOG.info("<recoveryFile> {} {} is exists, skip",snName, fileName);
				return true;
			}
		}
		remoteIndex = 0;
		for (String snsid : sss) {
			remoteIndex ++;
			//排除自己
			if (secondId.equals(snsid)) {
				LOG.debug("<recoveryFile> my sum is right,not need to do {} {} {}",fileName, secondId,snsid);
				continue;
			}
			
			remoteName = sim.getOtherFirstID(snsid, snId);
			if(BrStringUtils.isEmpty(remoteName)){
				LOG.debug("<recoveryFile> remote name is empty");
				continue;
			}
			remoteService = sm.getServiceById(Configs.getConfiguration().GetConfig(CommonConfigs.CONFIG_DATA_SERVICE_GROUP_NAME), remoteName);
			if(remoteService == null){
				LOG.debug("<recoveryFile> remote service is empty");
				continue;
			}
			remotePath = "/"+snName + "/" + remoteIndex + "/" + dirName + "/" + fileName;
			isSuccess = copyFrom(remoteService.getHost(), remoteService.getPort(),remoteService.getExtraPort(),5000, remotePath, dataPath + localPath);
			LOG.info("remote address [{}: {} ：{}], remote [{}], local [{}], stat [{}]",
				remoteService.getHost(), remoteService.getPort(), remoteService.getExtraPort(), 
				remotePath, localPath, isSuccess ? "success" :"fail");
			if(isSuccess){
				return true;
			}
		}
		return isSuccess;
	}
	
	/**
	 * 概述：判断serverID是否存在
	 * @param context
	 * @param second
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static int isContain(String[] context, String second) {
		if (context == null || context.length == 0 || BrStringUtils.isEmpty(second)) {
			return -1;
		}
		int i = 0;
		for (String str : context) {
			i++;
			if (BrStringUtils.isEmpty(str)) {
				continue;
			}
			if (second.equals(str)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 概述：获取文件列表
	 * @param fileName
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static List<String> getSNIds(String fileName) {
		if (BrStringUtils.isEmpty(fileName)) {
			return null;
		}

		String[] tmp = BrStringUtils.getSplit(fileName, "_");
		if (tmp == null || tmp.length == 0) {
			return null;
		}
		List<String> snIds = new ArrayList<String>();
		for (int i = 1; i < tmp.length; i++) {
			snIds.add(tmp[i]);
		}
		return snIds;
	}
	/**
	 * 概述：恢复数据文件
	 * @param host 远程主机
	 * @param port 端口
	 * @param export
	 * @param timeout
	 * @param remotePath
	 * @param localPath
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static boolean copyFrom(String host, int port,int export,int timeout, String remotePath, String localPath) {
		TcpDiskNodeClient client = null;
		BufferedOutputStream output = null;
		byte[] crcCode = null;
		byte[] data = null;
		int bufferSize = 5 * 1024 * 1024;
		boolean resultFlag = false;
		try {
			client = TcpClientUtils.getClient(host, port, export, timeout);
			LOG.warn("{}:{},{}:{}, read {} to local{}",host,port,host,export,remotePath,localPath);
			LocalByteStreamConsumer consumer = new LocalByteStreamConsumer(localPath);
			client.readFile(remotePath, consumer);
			return consumer.getResult().get();
		}catch (InterruptedException e) {
			return false;
		}
		catch (FileNotFoundException e) {
			return false;
		}
		catch (IOException e) {
			return false;
		}catch (ExecutionException e) {
			return false;
		}
		finally {
			if (client != null) {
				try {
					client.closeFile(remotePath);
					client.close();
				}
				catch (IOException e) {
					LOG.error("close error ", e);
				}
			}
			
		}
	}
}
